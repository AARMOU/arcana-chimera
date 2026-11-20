#!/usr/bin/env python3
"""Turn the arcana-table-rooms Cloudflare Worker into a pass-through to
Arcana's own room server (owner, Nov 17).

When the Worker variable ROOM_SERVER_ORIGIN is set (e.g.
https://rooms.arcanatablemtg.com), every request, including room WebSockets, is
forwarded there, so app builds that still use the old workers.dev address join
the same rooms as new builds. Each connection is one short Worker request; no
Durable Object holds a game. Without the variable the Worker behaves exactly as
before, and the GameRoom class stays exported so no storage migration is needed.
Apply AFTER patch_directed_room_resumption.py, to a temporary copy only.
"""
from pathlib import Path
import sys


def replace_once(source, old, new):
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Expected one Worker anchor, found {count}: {old[:80]!r}")
    return source.replace(old, new)


def patch(path):
    source = Path(path).read_text(encoding="utf-8")
    source = replace_once(source,
        "interface Env {\n  ROOMS: DurableObjectNamespace<GameRoom>;\n}",
        "interface Env {\n  ROOMS: DurableObjectNamespace<GameRoom>;\n  ROOM_SERVER_ORIGIN?: string;\n}")
    source = replace_once(source,
        "  async fetch(request: Request, env: Env): Promise<Response> {\n    const url = new URL(request.url);\n",
        "  async fetch(request: Request, env: Env): Promise<Response> {\n"
        "    const url = new URL(request.url);\n"
        "    if (env.ROOM_SERVER_ORIGIN) {\n"
        "      // Pass-through: old app builds reach Arcana's own room server.\n"
        "      const upstream = new URL(url.pathname + url.search, env.ROOM_SERVER_ORIGIN);\n"
        "      const headers = new Headers(request.headers);\n"
        "      headers.set(\"X-Arcana-Client-IP\", request.headers.get(\"CF-Connecting-IP\") ?? \"\");\n"
        "      return fetch(upstream.toString(), { method: request.method, headers });\n"
        "    }\n")
    Path(path).write_text(source, encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_room_worker_passthrough.py TEMPORARY_CLOUD_WORKER_INDEX_TS")
    patch(sys.argv[1])
    print("Room Worker pass-through STAGED (active only when ROOM_SERVER_ORIGIN is set).")
