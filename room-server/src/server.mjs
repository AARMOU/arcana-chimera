// Arcana self-hosted room server: same URLs and WebSocket protocol as the
// Cloudflare Worker (arcana-table-rooms), so the app talks to either one.
//   GET /health            JSON status
//   GET /room/{CODE}       WebSocket upgrade into that room
import http from "node:http";
import { WebSocketServer } from "ws";
import { GameRoom } from "../dist/room-worker.mjs";
import { RoomDisk, RoomRuntime, RoomSocket } from "./runtime.mjs";

const env = (name, fallback) => process.env[name] ?? fallback;
const PORT = Number(env("PORT", "8787"));
const HOST = env("HOST", "127.0.0.1");
const DATA_DIR = env("ROOM_DATA_DIR", "./data/rooms");
const MAX_SOCKETS = Number(env("MAX_SOCKETS", "50000"));
const MAX_SOCKETS_PER_IP = Number(env("MAX_SOCKETS_PER_IP", "200"));
const JOIN_TIMEOUT_MS = Number(env("JOIN_TIMEOUT_MS", "30000"));
const HEARTBEAT_MS = Number(env("HEARTBEAT_MS", "30000"));
const started = Date.now();

const runtime = new RoomRuntime({ GameRoom, disk: new RoomDisk(DATA_DIR) });
await runtime.disk.init();
const restored = await runtime.restore();

const perIp = new Map();
let openSockets = 0;

function roomCode(raw) {
  const code = String(raw).toUpperCase().replace(/[^A-Z0-9]/g, "");
  return /^[A-Z0-9]{6,8}$/.test(code) ? code : null;
}

function sendJson(res, status, value) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(value));
}

function clientIp(req) {
  // The server listens on localhost behind Cloudflare Tunnel, which sets cf-connecting-ip.
  // Old app builds arrive through the arcana-table-rooms pass-through Worker; Cloudflare
  // marks those with cf-worker, and the Worker passes the phone's own IP along.
  if (req.headers["cf-worker"] && req.headers["x-arcana-client-ip"]) return "via-worker:" + String(req.headers["x-arcana-client-ip"]);
  return String(req.headers["cf-connecting-ip"] ?? req.socket.remoteAddress ?? "unknown");
}

// One misbehaving connection must never take every game down with it.
process.on("uncaughtException", (error) => {
  console.error(JSON.stringify({ event: "uncaught", error: String(error?.stack ?? error) }));
});
process.on("unhandledRejection", (error) => {
  console.error(JSON.stringify({ event: "unhandled-rejection", error: String(error?.stack ?? error) }));
});

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname === "/health") {
    return sendJson(res, 200, {
      ok: !runtime.stopping, service: "arcana-table-rooms", protocol: 1, relay: 1,
      host: "self", ...runtime.stats(), uptimeSeconds: Math.round((Date.now() - started) / 1000),
    });
  }
  const match = url.pathname.match(/^\/room\/([A-Za-z0-9]{6,8})$/);
  if (!match) return sendJson(res, 404, { error: "Use /health or /room/{roomCode}." });
  if (!roomCode(match[1])) return sendJson(res, 400, { error: "Room codes must be 6 to 8 letters or numbers." });
  return sendJson(res, 426, { error: "A secure WebSocket connection is required." });
});
server.on("clientError", (_error, socket) => { socket.destroy(); });

const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024, perMessageDeflate: false });

server.on("upgrade", (req, socket, head) => {
  // A phone can drop mid-handshake; without this, its reset would crash the server.
  socket.on("error", () => socket.destroy());
  const reject = (status, text) => {
    socket.end(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  };
  const url = new URL(req.url ?? "/", "http://localhost");
  const match = url.pathname.match(/^\/room\/([A-Za-z0-9]{6,8})$/);
  const code = match ? roomCode(match[1]) : null;
  if (!code) return reject(404, "Not Found");
  if (runtime.stopping) return reject(503, "Service Unavailable");
  const ip = clientIp(req);
  if (openSockets >= MAX_SOCKETS || (perIp.get(ip) ?? 0) >= MAX_SOCKETS_PER_IP) return reject(429, "Too Many Requests");
  wss.handleUpgrade(req, socket, head, (ws) => {
    openSockets++;
    perIp.set(ip, (perIp.get(ip) ?? 0) + 1);
    const player = new RoomSocket(ws, ip);
    const host = runtime.accept(code, player);
    ws.isAlive = true;
    ws.on("pong", () => { ws.isAlive = true; });
    const joinTimer = setTimeout(() => {
      if (!player.deserializeAttachment()?.authenticated) player.close(4002, "Join took too long.");
    }, JOIN_TIMEOUT_MS);
    ws.on("message", (data, isBinary) => {
      const message = isBinary
        ? data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)
        : data.toString("utf8");
      runtime.message(host, player, message);
    });
    ws.on("error", () => { /* "close" always follows */ });
    ws.on("close", () => {
      clearTimeout(joinTimer);
      openSockets--;
      const left = (perIp.get(ip) ?? 1) - 1;
      if (left <= 0) perIp.delete(ip); else perIp.set(ip, left);
      runtime.closed(host, player);
    });
  });
});

// Detect phones that vanished without closing (the Worker runtime did this for us).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* close handler cleans up */ }
  }
}, HEARTBEAT_MS);

let stopping = null;
async function shutdown(signal) {
  if (stopping) return stopping;
  stopping = (async () => {
    console.log(JSON.stringify({ event: "stopping", signal, ...runtime.stats() }));
    clearInterval(heartbeat);
    server.close();
    await runtime.stop();
    setTimeout(() => process.exit(0), 500).unref();
  })();
  return stopping;
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

server.listen(PORT, HOST, () => {
  console.log(JSON.stringify({ event: "listening", host: HOST, port: PORT, restoredRooms: restored, dataDir: DATA_DIR }));
});
