// Runs Arcana's Cloudflare room Durable Object (room-worker.ts, unchanged) on
// plain Node.js. This file emulates only the parts of the Durable Object API the
// Worker uses: accepted WebSockets with attachments, key/value storage, one alarm,
// and one-event-at-a-time processing per room.
import { mkdir, readdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const OPEN = 1;

/** One player connection, shaped like a Cloudflare hibernatable WebSocket. */
export class RoomSocket {
  constructor(ws, ip) {
    this.ws = ws;
    this.ip = ip;
    this.attachment = null;
  }
  get readyState() { return this.ws.readyState; }
  send(text) {
    if (this.ws.readyState === OPEN) this.ws.send(text);
  }
  close(code = 1000, reason = "") {
    try { this.ws.close(code, String(reason).slice(0, 120)); } catch { this.ws.terminate(); }
  }
  serializeAttachment(value) { this.attachment = value === undefined ? null : structuredClone(value); }
  deserializeAttachment() { return this.attachment === null ? null : structuredClone(this.attachment); }
}

/** Atomic one-file-per-room persistence so rooms survive a server restart. */
export class RoomDisk {
  constructor(dir) { this.dir = dir; }
  async init() { await mkdir(this.dir, { recursive: true }); }
  file(code) { return path.join(this.dir, `${code}.json`); }
  async write(code, record) {
    const target = this.file(code);
    const temp = `${target}.${process.pid}.tmp`;
    await writeFile(temp, JSON.stringify(record));
    await rename(temp, target);
  }
  async remove(code) { await rm(this.file(code), { force: true }); }
  async loadAll() {
    const records = [];
    for (const name of await readdir(this.dir)) {
      if (!/^[A-Z0-9]{6,8}\.json$/.test(name)) {
        if (name.endsWith(".tmp")) await rm(path.join(this.dir, name), { force: true });
        continue;
      }
      try { records.push(JSON.parse(await readFile(path.join(this.dir, name), "utf8"))); }
      catch (error) { console.error(JSON.stringify({ event: "room-file-unreadable", name, error: String(error) })); }
    }
    return records;
  }
}

const MAX_TIMER_MS = 2 ** 31 - 1;

/** One room: the Worker's GameRoom instance plus its emulated runtime. */
class RoomHost {
  constructor(runtime, code) {
    this.runtime = runtime;
    this.code = code;
    this.sockets = new Set();
    this.data = new Map();
    this.alarmAt = null;
    this.alarmTimer = null;
    this.queue = Promise.resolve();
    this.dirty = false;
    this.flushing = null;
    const host = this;
    const storage = {
      async get(key) { return host.data.has(key) ? structuredClone(host.data.get(key)) : undefined; },
      async put(key, value) { host.data.set(key, structuredClone(value)); host.markDirty(); },
      async deleteAll() { host.data.clear(); host.setAlarm(null); host.markDirty(); },
      async setAlarm(when) { host.setAlarm(Number(when)); host.markDirty(); },
      async getAlarm() { return host.alarmAt; },
      async deleteAlarm() { host.setAlarm(null); host.markDirty(); },
    };
    this.ctx = {
      storage,
      acceptWebSocket: (socket) => { this.sockets.add(socket); },
      getWebSockets: () => [...this.sockets],
    };
    this.instance = new runtime.GameRoom(this.ctx, {});
  }

  /** Durable Objects process one event at a time; so does each room here. */
  run(task) {
    const next = this.queue.then(task).catch((error) => {
      console.error(JSON.stringify({ event: "room-error", room: this.code, error: String(error?.stack ?? error) }));
    });
    this.queue = next.then(() => this.maybeRetire());
    return next;
  }

  setAlarm(when) {
    if (this.alarmTimer) clearTimeout(this.alarmTimer);
    this.alarmTimer = null;
    this.alarmAt = Number.isFinite(when) ? when : null;
    if (this.alarmAt === null || this.runtime.stopping) return;
    const arm = () => {
      const wait = this.alarmAt - Date.now();
      if (wait > MAX_TIMER_MS) { this.alarmTimer = setTimeout(arm, MAX_TIMER_MS); return; }
      this.alarmTimer = setTimeout(() => {
        this.alarmTimer = null;
        this.alarmAt = null;
        this.run(() => this.instance.alarm());
      }, Math.max(0, wait));
    };
    arm();
  }

  markDirty() {
    this.dirty = true;
    if (!this.flushing) this.flushing = new Promise((resolve) => setTimeout(resolve, 50)).then(() => this.flush());
  }

  async flush() {
    try {
      while (this.dirty) {
        this.dirty = false;
        if (this.data.size === 0) await this.runtime.disk.remove(this.code);
        else await this.runtime.disk.write(this.code, { code: this.code, alarmAt: this.alarmAt, data: Object.fromEntries(this.data) });
      }
    } catch (error) {
      console.error(JSON.stringify({ event: "room-save-failed", room: this.code, error: String(error) }));
    } finally {
      this.flushing = null;
      this.maybeRetire();
    }
  }

  maybeRetire() {
    if (this.data.size === 0 && this.sockets.size === 0 && !this.dirty && !this.flushing && this.alarmAt === null) {
      this.runtime.rooms.delete(this.code);
    }
  }
}

export class RoomRuntime {
  constructor({ GameRoom, disk }) {
    this.GameRoom = GameRoom;
    this.disk = disk;
    this.rooms = new Map();
    this.stopping = false;
  }

  room(code) {
    let host = this.rooms.get(code);
    if (!host) { host = new RoomHost(this, code); this.rooms.set(code, host); }
    return host;
  }

  /**
   * After a restart every phone's socket is gone. Replay exactly what the Worker
   * does when sockets drop, so directed rooms keep their 45-minute recovery
   * window and their private seat tokens, and phones reconnect to the same seat.
   */
  async restore() {
    const records = await this.disk.loadAll();
    for (const record of records) {
      if (!record?.code || !record.data) continue;
      const host = this.room(record.code);
      for (const [key, value] of Object.entries(record.data)) host.data.set(key, value);
      host.setAlarm(Number.isFinite(record.alarmAt) ? record.alarmAt : Date.now());
      await host.run(async () => {
        const room = await host.ctx.storage.get("room");
        host.instance.room = room ?? null;
        for (let seat = 0; room && seat < room.seats.length; seat++) {
          if (host.instance.room && room.seats[seat]?.online) await host.instance.setOffline(seat);
        }
        host.markDirty();
      });
    }
    return records.length;
  }

  accept(code, socket) {
    const host = this.room(code);
    host.ctx.acceptWebSocket(socket);
    socket.serializeAttachment({ seat: -1, code, authenticated: false });
    return host;
  }

  message(host, socket, message) { return host.run(() => host.instance.webSocketMessage(socket, message)); }

  closed(host, socket) {
    host.sockets.delete(socket);
    if (this.stopping) return Promise.resolve();
    return host.run(() => host.instance.webSocketClose(socket));
  }

  stats() {
    let sockets = 0;
    for (const host of this.rooms.values()) sockets += host.sockets.size;
    return { rooms: this.rooms.size, sockets };
  }

  /** Save every room, then drop connections WITHOUT marking seats offline. */
  async stop() {
    this.stopping = true;
    for (const host of this.rooms.values()) { if (host.alarmTimer) clearTimeout(host.alarmTimer); }
    await Promise.all([...this.rooms.values()].map(async (host) => {
      await host.queue;
      host.dirty = true;
      await host.flush();
    }));
    for (const host of this.rooms.values()) for (const socket of host.sockets) socket.close(1012, "Room server restarting; reconnect.");
  }
}
