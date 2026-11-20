// Runs the Cloudflare Worker's own relay tests, Chimera's recovery tests and a
// server-restart test against the self-hosted Node room server.
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const here = path.dirname(fileURLToPath(import.meta.url));
const serverDir = path.resolve(here, "..");
const repo = path.resolve(serverDir, "..");
const port = 18000 + Math.floor(Math.random() * 1000);
const origin = `http://127.0.0.1:${port}`;
const dataDir = await mkdtemp(path.join(tmpdir(), "arcana-rooms-"));
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let server = null;
let serverLog = "";
async function startServer() {
  server = spawn(process.execPath, [path.join(serverDir, "src/server.mjs")], {
    env: { ...process.env, PORT: String(port), ROOM_DATA_DIR: dataDir, HEARTBEAT_MS: "2000" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  server.stdout.on("data", (chunk) => { serverLog += chunk; });
  server.stderr.on("data", (chunk) => { serverLog += chunk; });
  for (let attempt = 0; attempt < 100; attempt++) {
    try { if ((await fetch(`${origin}/health`)).ok) return; } catch { /* not up yet */ }
    await delay(100);
  }
  throw new Error("Room server did not start:\n" + serverLog);
}
async function stopServer(signal = "SIGTERM") {
  const exited = new Promise((resolve) => server.once("exit", resolve));
  server.kill(signal);
  await exited;
}
function runNode(file) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [file], { env: { ...process.env, ARCANA_RELAY_TEST_ORIGIN: origin }, stdio: "inherit" });
    child.on("exit", (code) => code === 0 ? resolve() : reject(new Error(`${path.basename(file)} failed (${code})`)));
  });
}

async function dial(code, name) {
  const socket = new WebSocket(`${origin.replace(/^http/, "ws")}/room/${code}`);
  const received = [];
  socket.addEventListener("message", (event) => received.push(JSON.parse(event.data)));
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", () => reject(new Error(`${name} could not connect`)), { once: true });
  });
  return {
    socket,
    send: (body) => socket.send(JSON.stringify(body)),
    async wait(match, timeout = 5000) {
      const until = Date.now() + timeout;
      for (;;) {
        const index = received.findIndex(match);
        if (index >= 0) return received.splice(index, 1)[0];
        if (Date.now() > until) throw new Error(`${name} timed out; got ${JSON.stringify(received.map((p) => p.type))}`);
        await delay(20);
      }
    },
  };
}
const type = (name) => (packet) => packet.type === name;
const answer = (packet) => packet.type === "welcome" || packet.type === "rejected";

/** A live directed game must survive a server restart with the same seats and tokens. */
async function restartTest() {
  const code = ("R" + crypto.randomUUID().replace(/-/g, "").slice(0, 7)).toUpperCase();
  const password = "arcana-" + code;
  const host = await dial(code, "host");
  host.send({ type: "create", code, password, name: "Host" });
  const hostWelcome = await host.wait(type("welcome"));
  host.send({ type: "relaySeats", kinds: ["friend", "ai"] });
  await host.wait(type("relay-seats-ack"));
  const friend = await dial(code, "friend");
  friend.send({ type: "join", code, password, name: "Friend" });
  const friendWelcome = await friend.wait(type("welcome"));
  assert.equal(friendWelcome.seat, 1);

  await stopServer();
  await startServer();

  const intruder = await dial(code, "intruder");
  intruder.send({ type: "join", code, password, name: "Intruder", preferredSeat: 0, seatToken: "wrong" });
  assert.equal((await intruder.wait(answer)).type, "rejected", "a wrong token must never take a seat after restart");

  const host2 = await dial(code, "host-after-restart");
  host2.send({ type: "join", code, password, name: "Host", preferredSeat: 0, seatToken: hostWelcome.seatToken });
  const h2 = await host2.wait(answer);
  assert.equal(h2.type, "welcome", "host must rejoin its seat after a restart: " + JSON.stringify(h2));
  assert.equal(h2.seat, 0);
  const friend2 = await dial(code, "friend-after-restart");
  friend2.send({ type: "join", code, password, name: "Friend", preferredSeat: 1, seatToken: friendWelcome.seatToken });
  const f2 = await friend2.wait(answer);
  assert.equal(f2.type, "welcome", "friend must rejoin its seat after a restart: " + JSON.stringify(f2));
  assert.equal(f2.seat, 1);

  host2.send({ type: "relay", to: 1, seq: 1, data: "hidden-view" });
  const relayed = await friend2.wait(type("relay"));
  assert.equal(relayed.data, "hidden-view");
  for (const peer of [intruder, host2, friend2]) peer.socket.close();
  console.log("PASS restart: room, seats and private seat tokens survived a server restart");
}

async function healthTest() {
  const health = await (await fetch(`${origin}/health`)).json();
  assert.equal(health.service, "arcana-table-rooms");
  assert.equal(health.relay, 1);
  assert.equal((await fetch(`${origin}/room/ABCDEF`)).status, 426);
  assert.equal((await fetch(`${origin}/nope`)).status, 404);
  console.log("PASS health and routing match the Worker");
}

try {
  await startServer();
  await healthTest();
  await runNode(path.join(here, "vendor-verify-directed-relay.mjs"));
  for (const name of ["verify-directed-room-all-offline.mjs", "verify-directed-room-live-takeover.mjs", "verify-directed-room-repeat-takeover.mjs"]) {
    await runNode(path.join(repo, "tests", name));
  }
  await restartTest();
  console.log("ALL ROOM SERVER TESTS PASSED");
} catch (error) {
  console.error(String(error?.stack ?? error));
  console.error("--- server log ---\n" + serverLog.slice(-4000));
  process.exitCode = 1;
} finally {
  if (server && server.exitCode === null) await stopServer();
  await rm(dataDir, { recursive: true, force: true });
}
