/**
 * Against wrangler dev --local: after every WebSocket drops (phone locked),
 * the original host may reclaim seat 0 with its private credential.
 * No game state is invented and no seat token may be reused by a stranger.
 */
import assert from "node:assert/strict";

const origin = process.env.ARCANA_RELAY_TEST_ORIGIN || "http://127.0.0.1:8787";
const code = ("L" + crypto.randomUUID().replace(/-/g, "").slice(0, 7)).toUpperCase();
const url = origin.replace(/^http/, "ws") + "/room/" + code;
const password = "arcana-argentum-" + code;
const sockets = [];

function delay(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function dial(name) {
  const socket = new WebSocket(url);
  sockets.push(socket);
  const received = [];
  const subscriptions = [];
  socket.addEventListener("message", (event) => {
    const packet = JSON.parse(event.data);
    const n = subscriptions.findIndex((s) => s.match(packet));
    if (n >= 0) {
      const s = subscriptions.splice(n, 1)[0];
      clearTimeout(s.timer);
      s.resolve(packet);
    } else received.push(packet);
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(name + " connect timeout")), 7000);
    socket.addEventListener("open", () => { clearTimeout(timer); resolve(); }, { once: true });
    socket.addEventListener("error", () => { clearTimeout(timer); reject(Error(name + " socket error")); }, { once: true });
  });
  return {
    socket,
    send: (body) => socket.send(JSON.stringify(body)),
    wait: (match, timeout = 7000) => {
      const ready = received.findIndex(match);
      if (ready >= 0) return Promise.resolve(received.splice(ready, 1)[0]);
      return new Promise((resolve, reject) => {
        const entry = { match, resolve, timer: null };
        entry.timer = setTimeout(() => {
          const i = subscriptions.indexOf(entry);
          if (i >= 0) subscriptions.splice(i, 1);
          reject(Error(name + " timeout waiting for response"));
        }, timeout);
        subscriptions.push(entry);
      });
    },
  };
}
const type = (name) => (packet) => packet.type === name;
try {
  const host = await dial("host");
  host.send({ type: "create", code, password, name: "Host" });
  const h = await host.wait(type("welcome"));
  assert.equal(h.seat, 0);
  assert.ok(h.seatToken);
  host.send({ type: "relaySeats", kinds: ["friend", "ai", "friend"] });
  await host.wait(type("relay-seats-ack"));

  const friend = await dial("friend");
  friend.send({ type: "join", code, password, name: "Friend" });
  const f = await friend.wait(type("welcome"));
  assert.equal(f.seat, 1);
  assert.ok(f.seatToken);

  // Simulate fold/lock: all sockets end without an intentional "leave".
  host.socket.close();
  await friend.wait((p) => p.type === "state" && p.payload?.players?.[0]?.isOnline === false);
  friend.socket.close();
  await delay(600); // Allow the durable object to process both close events.

  const intruder = await dial("bad-token");
  intruder.send({ type: "join", code, password, name: "Intruder",
    preferredSeat: 0, seatToken: "FAKE-" + h.seatToken });
  assert.match((await intruder.wait(type("rejected"))).reason, /seat.*verif/i);

  const hostBack = await dial("host-resume");
  hostBack.send({ type: "join", code, password, name: "Host",
    preferredSeat: 0, seatToken: h.seatToken });
  const hr = await hostBack.wait(type("welcome"));
  assert.equal(hr.seat, 0);
  assert.equal(hr.seatToken, h.seatToken);

  const friendBack = await dial("friend-resume");
  friendBack.send({ type: "join", code, password, name: "Friend",
    preferredSeat: 1, seatToken: f.seatToken });
  assert.equal((await friendBack.wait(type("welcome"))).seat, 1);
  hostBack.send({ type: "relay", to: 1, seq: 12, data: "LOCK_TEST_RECOVERED" });
  assert.equal((await friendBack.wait(type("relay"))).data, "LOCK_TEST_RECOVERED");
  console.log("PASS: all-offline hold, no forged host seat, host/guest secure resume, private relay.");
} finally {
  for (const s of sockets) try { s.close(); } catch {}
}
