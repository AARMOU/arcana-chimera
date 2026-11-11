#!/usr/bin/env python3
"""Patch a TEMPORARY copy of Arcana's cloud-room Worker for directed-room recovery.

Never deploys. A Cloudflare Worker update requires separate owner approval.
"""
from pathlib import Path
import sys


def replace_once(source, old, new):
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Expected one Worker source anchor, found {count}: {old[:100]!r}")
    return source.replace(old, new)


def patch_worker(path):
    source = Path(path).read_text(encoding="utf-8")
    source = replace_once(source,
        '  relayMode?: boolean;\n}',
        '  relayMode?: boolean;\n  offlineDeadline?: number; // Bounded recovery window for screen-locked directed hosts.\n}')
    source = replace_once(source,
        'const ROOM_TTL_MS = 24 * 60 * 60 * 1000;',
        'const ROOM_TTL_MS = 24 * 60 * 60 * 1000;\nconst DIRECTED_ROOM_RECOVERY_MS = 45 * 60 * 1000;')
    source = replace_once(source,
        '''  async alarm(): Promise<void> {
    this.room = null;
    await this.ctx.storage.deleteAll();
  }''',
        '''  async alarm(): Promise<void> {
    this.room = await this.ctx.storage.get<RoomRecord>(ROOM_KEY) ?? null;
    if (this.room && this.room.relayMode &&
        this.room.offlineDeadline && Date.now() < this.room.offlineDeadline) {
      await this.ctx.storage.setAlarm(this.room.offlineDeadline);
      return;
    }
    this.room = null;
    await this.ctx.storage.deleteAll();
  }''')
    source = replace_once(source,
        '''    if (this.room && !this.room.seats.some((candidate) => candidate.online)) {
      this.room = null;
      await this.ctx.storage.deleteAll();
    }''',
        '''    if (this.room && !this.room.seats.some((candidate) => candidate.online) &&
        (!this.room.relayMode || !this.room.offlineDeadline ||
         Date.now() >= this.room.offlineDeadline)) {
      this.room = null;
      await this.ctx.storage.deleteAll();
    }''')
    source = replace_once(source,
        '''    existing.online = true;
    if (this.room.relayMode) existing.claimed = true;''',
        '''    existing.online = true;
    this.room.offlineDeadline = undefined;
    if (this.room.relayMode) existing.claimed = true;''')
    source = replace_once(source,
        '''    await this.setOffline(seat);
    this.send(socket, { type: "left" });''',
        '''    await this.setOffline(seat, true);
    this.send(socket, { type: "left" });''')
    source = replace_once(source,
        '''    if (attachment?.authenticated && Number.isInteger(attachment.seat)) await this.setOffline(attachment.seat!);
  }

  private async setOffline(seat: number): Promise<void> {''',
        '''    if (attachment?.authenticated && Number.isInteger(attachment.seat)) {
      // An old socket's late close must NOT evict a newer authorized socket
      // that has already reclaimed this same seat.
      const successor = this.ctx.getWebSockets().some((other) => {
        if (other === socket || other.readyState !== WebSocket.OPEN) return false;
        const state = other.deserializeAttachment() as { seat?: number; authenticated?: boolean } | null;
        return state?.authenticated && state.seat === attachment.seat;
      });
      if (!successor) await this.setOffline(attachment.seat!);
    }
  }

  private async setOffline(seat: number, explicitLeave = false): Promise<void> {''')
    source = replace_once(source,
        '    if (this.room.state.gameStarted === true && Number(this.room.state.activePlayer ?? -1) === seat) {',
        '    if (!this.room.relayMode && this.room.state.gameStarted === true && Number(this.room.state.activePlayer ?? -1) === seat) {')
    source = replace_once(source,
        '''    if (!this.room.seats.some((candidate) => candidate.online)) {
      this.room = null;
      await this.ctx.storage.deleteAll();
      return;
    }
    await this.save();''',
        '''    if (!this.room.seats.some((candidate) => candidate.online)) {
      if (!this.room.relayMode || explicitLeave) {
        this.room = null;
        await this.ctx.storage.deleteAll();
        return;
      }
      // Screen-off/Doze can disconnect every phone. Retain room code,
      // seat tokens and game identity for bounded secure reconnection.
      this.room.offlineDeadline = Date.now() + DIRECTED_ROOM_RECOVERY_MS;
    }
    await this.save();''')
    source = replace_once(source,
        '''    await this.ctx.storage.put(ROOM_KEY, this.room);
    await this.ctx.storage.setAlarm(Date.now() + ROOM_TTL_MS);''',
        '''    await this.ctx.storage.put(ROOM_KEY, this.room);
    await this.ctx.storage.setAlarm(this.room.offlineDeadline ?? (Date.now() + ROOM_TTL_MS));''')
    Path(path).write_text(source, encoding="utf-8")
    return source


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_directed_room_resumption.py TEMPORARY_CLOUD_WORKER_INDEX_TS")
    patch_worker(sys.argv[1])
    print("Cloud room resume fix STAGED only. OWNER APPROVAL REQUIRED FOR DEPLOYMENT.")
