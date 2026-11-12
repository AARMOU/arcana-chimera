/**
 * Regression: multiple token-authenticated host/guest socket replacements.
 *
 * Run against the patched directed-room Worker via wrangler dev --local.
 * The first takeover is covered by verify-directed-room-live-takeover.mjs.
 * This test exercises replacement of an ALREADY replaced socket, including
 * its delayed closure and exactly-once delivery to the newest connection.
 *
 * Cloudflare deployment and physical Android/Fold behavior are NOT tested.
 */
import assert from "node:assert/strict";

const origin = process.env.ARCANA_RELAY_TEST_ORIGIN || "http://127.0.0.1:8787";
const code = ("R" + crypto.randomUUID().replace(/-/g, "").slice(0, 7)).toUpperCase();
const url = origin.replace(/^http/, "ws") + "/room/" + code;
const password = "arcana-argentum-" + code;
const sockets = [];
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const byType = (type) => (packet) => packet.type === type;
const answer = (packet) => packet.type === "welcome" || packet.type === "rejected";

async function dial(name) {
  const socket = new WebSocket(url);
  sockets.push(socket);
  const packets = [];
  let closed = null;
  socket.addEventListener("message", (event) => packets.push(JSON.parse(event.data)));
  socket.addEventListener("close", (event) => { closed = event.code; });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(name + ": connect timeout")), 7000);
    socket.addEventListener("open", () => { clearTimeout(timer); resolve(); }, { once: true });
    socket.addEventListener("error", () => { clearTimeout(timer); reject(Error(name + ": socket error")); }, { once: true });
  });
  return {
    socket, packets, closedWith: () => closed,
    send: (packet) => socket.send(JSON.stringify(packet)),
    async wait(match, timeout = 7000) {
      const until = Date.now() + timeout;
      while (Date.now() <= until) {
        const index = packets.findIndex(match);
        if (index >= 0) return packets.splice(index, 1)[0];
        await delay(20);
      }
      throw Error(name + ": timeout waiting for packet");
    },
  };
}
function falseOffline(packets, seat) {
  return packets.some((p) =>
    p.type === "state" && p.payload?.players?.[seat]?.isOnline === false);
}
function relayCount(packets, data) {
  return packets.filter((p) => p.type === "relay" && p.data === data).length;
}

try {
  const host0 = await dial("host-0");
  host0.send({ type: "create", code, password, name: "Host" });
  const initialHost = await host0.wait(byType("welcome"));
  assert.equal(initialHost.seat, 0);
  assert.ok(initialHost.seatToken, "host must receive a private seat token");
  host0.send({ type: "relaySeats", kinds: ["friend", "ai", "friend"] });
  await host0.wait(byType("relay-seats-ack"));

  const guest0 = await dial("guest-0");
  guest0.send({ type: "join", code, password, name: "Guest" });
  const initialGuest = await guest0.wait(byType("welcome"));
  assert.equal(initialGuest.seat, 1);
  assert.ok(initialGuest.seatToken, "guest must receive a private seat token");

  // Host replacement #1: prior socket still appears online.
  const host1 = await dial("host-1");
  host1.send({ type: "join", code, password, name: "Host",
    preferredSeat: 0, seatToken: initialHost.seatToken });
  const first = await host1.wait(answer);
  assert.equal(first.type, "welcome", first.reason);
  assert.equal(first.seatToken, initialHost.seatToken);

  // Host replacement #2: takeover of the replacement before the prior close
  // has any chance to mark seat zero offline or revoke its private identity.
  const host2 = await dial("host-2");
  host2.send({ type: "join", code, password, name: "Host",
    preferredSeat: 0, seatToken: initialHost.seatToken });
  const second = await host2.wait(answer);
  assert.equal(second.type, "welcome", second.reason);
  assert.equal(second.seat, 0);
  assert.equal(second.seatToken, initialHost.seatToken);

  // A previous valid token for a DIFFERENT seat remains insufficient.
  const wrongSeat = await dial("wrong-seat");
  wrongSeat.send({ type: "join", code, password, name: "Impostor",
    preferredSeat: 0, seatToken: initialGuest.seatToken });
  assert.equal((await wrongSeat.wait(answer)).type, "rejected");

  // Let both retired socket close callbacks arrive.
  await delay(900);
  assert.notEqual(host0.closedWith(), null, "original host socket not retired");
  assert.notEqual(host1.closedWith(), null, "first replacement host socket not retired");
  assert.equal(falseOffline(guest0.packets, 0), false,
    "late host close broadcast a false host-offline state");

  // Exactly one guest action may reach the *latest* host socket.
  guest0.send({ type: "relay", to: 0, seq: 310, data: "REPEAT_GUEST_TO_HOST" });
  const deliveredHost = await host2.wait((p) =>
    p.type === "relay" && p.data === "REPEAT_GUEST_TO_HOST");
  assert.equal(deliveredHost.data, "REPEAT_GUEST_TO_HOST");
  await delay(250);
  assert.equal(relayCount(host2.packets, "REPEAT_GUEST_TO_HOST"), 0,
    "duplicate guest relay delivered to live host");
  assert.equal(relayCount(host0.packets, "REPEAT_GUEST_TO_HOST"), 0,
    "retired original host received private relay");
  assert.equal(relayCount(host1.packets, "REPEAT_GUEST_TO_HOST"), 0,
    "retired first replacement received private relay");

  // Now repeat the same race for guest seat one.
  const guest1 = await dial("guest-1");
  guest1.send({ type: "join", code, password, name: "Guest",
    preferredSeat: 1, seatToken: initialGuest.seatToken });
  assert.equal((await guest1.wait(answer)).type, "welcome");

  const guest2 = await dial("guest-2");
  guest2.send({ type: "join", code, password, name: "Guest",
    preferredSeat: 1, seatToken: initialGuest.seatToken });
  const lastGuest = await guest2.wait(answer);
  assert.equal(lastGuest.type, "welcome", lastGuest.reason);
  assert.equal(lastGuest.seatToken, initialGuest.seatToken);
  await delay(900);
  assert.notEqual(guest0.closedWith(), null, "original guest socket not retired");
  assert.notEqual(guest1.closedWith(), null, "first replacement guest socket not retired");
  assert.equal(falseOffline(host2.packets, 1), false,
    "late guest close broadcast a false guest-offline state");

  host2.send({ type: "relay", to: 1, seq: 311, data: "REPEAT_HOST_TO_GUEST" });
  assert.equal((await guest2.wait((p) =>
    p.type === "relay" && p.data === "REPEAT_HOST_TO_GUEST")).data,
    "REPEAT_HOST_TO_GUEST");
  await delay(250);
  assert.equal(relayCount(guest2.packets, "REPEAT_HOST_TO_GUEST"), 0,
    "duplicate host relay delivered to live guest");
  assert.equal(relayCount(guest0.packets, "REPEAT_HOST_TO_GUEST"), 0,
    "retired original guest received private relay");
  assert.equal(relayCount(guest1.packets, "REPEAT_HOST_TO_GUEST"), 0,
    "retired first replacement guest received private relay");

  // Both newest sockets remain usable after all stale callbacks.
  guest2.send({ type: "relay", to: 0, seq: 312, data: "STILL_ALIVE" });
  assert.equal((await host2.wait((p) => p.type === "relay" && p.data === "STILL_ALIVE")).data,
    "STILL_ALIVE");
  console.log("PASS: repeated authenticated host and guest takeovers keep private relays on latest sockets.");
} finally {
  for (const socket of sockets) {
    try { socket.close(); } catch { /* best-effort teardown */ }
  }
}
