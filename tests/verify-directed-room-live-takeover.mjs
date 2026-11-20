/**
 * Against wrangler dev --local: a phone that reconnects BEFORE the server has
 * noticed its old socket die (half-open after a lock or network change) must
 * get its own seat back with its private token. Device log 2026-11-11 showed
 * this rejoin refused with "Your saved player seat could not be verified",
 * which ended live games about a minute in.
 */
import assert from "node:assert/strict";

const origin = process.env.ARCANA_RELAY_TEST_ORIGIN || "http://127.0.0.1:8787";
const code = ("T" + crypto.randomUUID().replace(/-/g, "").slice(0, 7)).toUpperCase();
const url = origin.replace(/^http/, "ws") + "/room/" + code;
const password = "arcana-argentum-" + code;
const sockets = [];
const delay = (ms) => new Promise((r) => setTimeout(r, ms));

async function dial(name) {
  const socket = new WebSocket(url);
  sockets.push(socket);
  const received = [];
  let closed = null;
  socket.addEventListener("message", (event) => received.push(JSON.parse(event.data)));
  socket.addEventListener("close", (event) => { closed = event.code; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(name + " connect timeout")), 7000);
    socket.addEventListener("open", () => { clearTimeout(timer); resolve(); }, { once: true });
    socket.addEventListener("error", () => { clearTimeout(timer); reject(Error(name + " socket error")); }, { once: true });
  });
  return {
    socket, received,
    closedWith: () => closed,
    send: (body) => socket.send(JSON.stringify(body)),
    async wait(match, timeout = 7000) {
      const until = Date.now() + timeout;
      for (;;) {
        const n = received.findIndex(match);
        if (n >= 0) return received.splice(n, 1)[0];
        if (Date.now() > until) throw Error(name + " timeout waiting for response");
        await delay(20);
      }
    },
  };
}
const type = (name) => (packet) => packet.type === name;
const answer = (packet) => packet.type === "welcome" || packet.type === "rejected";

try {
  const host = await dial("host");
  host.send({ type: "create", code, password, name: "Host" });
  const h = await host.wait(type("welcome"));
  host.send({ type: "relaySeats", kinds: ["friend", "ai", "friend"] });
  await host.wait(type("relay-seats-ack"));

  const friend = await dial("friend");
  friend.send({ type: "join", code, password, name: "Friend" });
  const f = await friend.wait(type("welcome"));
  assert.equal(f.seat, 1);

  // A stranger with the room code but not the token may never take a live seat.
  const intruder = await dial("intruder");
  intruder.send({ type: "join", code, password, name: "Intruder", preferredSeat: 0, seatToken: "FAKE-" + h.seatToken });
  assert.equal((await intruder.wait(answer)).type, "rejected");
  const swapped = await dial("wrong-seat-token");
  swapped.send({ type: "join", code, password, name: "Friend", preferredSeat: 0, seatToken: f.seatToken });
  assert.equal((await swapped.wait(answer)).type, "rejected");

  // The host's old socket is STILL OPEN as far as the server knows.
  const hostBack = await dial("host-resume");
  hostBack.send({ type: "join", code, password, name: "Host", preferredSeat: 0, seatToken: h.seatToken });
  const back = await hostBack.wait(answer);
  assert.equal(back.type, "welcome", "host rejoin refused while old socket lingered: " + back.reason);
  assert.equal(back.seat, 0);
  assert.equal(back.seatToken, h.seatToken);

  // The replaced socket is retired and its close must not take the seat offline.
  await delay(800);
  assert.notEqual(host.closedWith(), null, "replaced host socket was left open");
  assert.ok(!friend.received.some((p) => p.type === "state" && p.payload?.players?.[0]?.isOnline === false),
    "guest was told the host went offline during takeover");

  // The game carries on over the new socket, in both directions.
  hostBack.send({ type: "relay", to: 1, seq: 21, data: "TAKEOVER_HOST_TO_GUEST" });
  assert.equal((await friend.wait(type("relay"))).data, "TAKEOVER_HOST_TO_GUEST");
  friend.send({ type: "relay", to: 0, seq: 22, data: "TAKEOVER_GUEST_TO_HOST" });
  assert.equal((await hostBack.wait(type("relay"))).data, "TAKEOVER_GUEST_TO_HOST");
  assert.ok(!host.received.some((p) => p.type === "relay"), "retired socket still received private relay");

  // Same for a guest whose old socket lingers.
  const friendBack = await dial("friend-resume");
  friendBack.send({ type: "join", code, password, name: "Friend", preferredSeat: 1, seatToken: f.seatToken });
  assert.equal((await friendBack.wait(answer)).type, "welcome");
  hostBack.send({ type: "relay", to: 1, seq: 23, data: "TAKEOVER_GUEST_RESUMED" });
  assert.equal((await friendBack.wait(type("relay"))).data, "TAKEOVER_GUEST_RESUMED");
  console.log("PASS: token holder takes over a lingering seat; strangers refused; relay continues.");
} finally {
  for (const s of sockets) try { s.close(); } catch {}
}
