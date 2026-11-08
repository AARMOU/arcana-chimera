#!/usr/bin/env python3
"""Chimera bug-hunt foundation: static debt guard and deterministic trace triage.

Stdlib-only; read-only. No network, no APKs, no GitHub writes, no user data uploads.
This is NOT a complete Magic rules validator. See docs/BUG-HUNTING.md.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
HOST = "forge-host/src/main/java/org/arcanachimera/forgehost/ArcanaForgeHost.java"
BASELINE = "bughunt/source-risk-baseline.json"
STATE_RE = re.compile(
    r"(?:Argentum|Forge) state v(\d+) applied: \d+ players?, turn (\d+) ([A-Z_ ]+), (\d+) legal actions"
)
DIAG_RE = re.compile(r"^#\d+\s+(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\[([^\]]+)\]\s+(.*)$")


def audit(root: Path) -> dict:
    """Compare explicit source hazards with reviewed counts; fail on *new* matches."""
    rules = json.loads((root / BASELINE).read_text(encoding="utf-8"))
    results = []
    for rule in rules["checks"]:
        source = root / rule.get("path", HOST)
        if not source.is_file():
            raise ValueError(f"missing audited source: {source}")
        count = len(re.findall(rule["pattern"], source.read_text(encoding="utf-8")))
        known = int(rule["allowed"])
        results.append({
            "id": rule["id"], "severity": rule["severity"], "found": count,
            "baseline": known, "status": "NEW_RISK" if count > known else
            ("REDUCED" if count < known else "KNOWN_GAP"),
            "why": rule["why"],
        })
    return {
        "mode": "source-audit",
        "passed": not any(x["status"] == "NEW_RISK" for x in results),
        "scope": HOST,
        "checks": results,
        "note": "Pattern counts are a narrow regression tripwire, not a proof of safety.",
    }


def fingerprint(event: dict) -> tuple:
    """Only observable progression fields. Never include private card information."""
    return tuple(event.get(k) for k in
                 ("turn", "phase", "active", "priority", "pending", "legal", "board_revision"))


def analyze(events: list[dict], stall_ms: int = 10000) -> dict:
    """Analyze normalized JSONL. A state version increase ALONE is not progress.

    An action is cleared only by a materially different observable state, or an
    explicit rejection/error. Timeouts require a later observation/tick.
    """
    if stall_ms < 1:
        raise ValueError("stall-ms must be positive")
    findings = []
    last_ms = -1
    last_version = None
    last_state = None
    outstanding = None
    counts = {"states": 0, "actions": 0, "errors": 0, "ticks": 0}
    stalled_actions = set()
    # Consecutive errors from the same action without material state progress.
    rejection = None

    def flag(code: str, severity: str, at_ms: int, detail: str) -> None:
        findings.append({"code": code, "severity": severity, "at_ms": at_ms, "detail": detail})

    for index, event in enumerate(events, 1):
        if not isinstance(event, dict):
            raise ValueError(f"event {index} must be an object")
        when = event.get("at_ms")
        if not isinstance(when, int) or isinstance(when, bool) or when < last_ms:
            raise ValueError(f"event {index} has missing/nonmonotonic integer at_ms")
        last_ms = when
        kind = event.get("kind")
        if kind == "state":
            counts["states"] += 1
            version = event.get("version")
            if not isinstance(version, int) or isinstance(version, bool) or version <= 0:
                raise ValueError(f"event {index} has invalid state version")
            if last_version is not None and version <= last_version:
                flag("NON_MONOTONIC_VERSION", "P0", when,
                     f"state version {version} after {last_version}")
            last_version = version
            current = fingerprint(event)
            if outstanding and current != outstanding["before"]:
                outstanding = None
            if rejection and current != rejection["before"]:
                rejection = None
            last_state = event
        elif kind == "action":
            counts["actions"] += 1
            if outstanding and outstanding["index"] not in stalled_actions:
                flag("ACTION_SUPERSEDED_WITHOUT_PROGRESS", "P1", when,
                     f"{outstanding['name']} superseded before observable progress")
            name = str(event.get("name", "unknown"))
            before = fingerprint(last_state) if last_state else None
            if rejection and (rejection["name"] != name or rejection["before"] != before):
                rejection = None
            outstanding = {"at_ms": when, "name": name,
                           "before": before, "index": index}
        elif kind == "error":
            counts["errors"] += 1
            flag("ENGINE_REJECTION_OR_ERROR", "P1", when,
                 "Engine reported an error; inspect a private diagnostic locally.")
            if outstanding:
                name, before = outstanding["name"], outstanding["before"]
                if rejection and rejection["name"] == name and rejection["before"] == before:
                    rejection["count"] += 1
                else:
                    rejection = {"name": name, "before": before, "count": 1}
                if rejection["count"] == 3:
                    flag("REPEATED_REJECTED_ACTION", "P0", when,
                         f"{name} rejected 3 consecutive times without observable game progress")
            outstanding = None  # an explicit rejection is not an invisible hang
        elif kind == "tick":
            counts["ticks"] += 1
        else:
            raise ValueError(f"event {index} has unknown kind: {kind!r}")
        if outstanding and when - outstanding["at_ms"] >= stall_ms:
            if outstanding["index"] not in stalled_actions:
                flag("NO_OBSERVABLE_PROGRESS", "P0", when,
                     f"{outstanding['name']} has no observable progression for "
                     f"{when - outstanding['at_ms']} ms")
                stalled_actions.add(outstanding["index"])
    return {
        "mode": "trace", "passed": not any(f["severity"] == "P0" for f in findings),
        "counts": counts, "last_version": last_version, "findings": findings,
        "note": "A flagged stall or repeated rejection identifies a suspect handoff, not necessarily an AI bug.",
    }


def parse_jsonl(path: Path) -> list[dict]:
    events = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"line {n}: invalid JSON: {exc.msg}") from exc
        events.append(event)
    if not events:
        raise ValueError("no trace events found")
    return events



def parse_live_log(path: Path, session: str | None = None) -> list[dict]:
    """Read only BUGHUNT_EVENT JSONL emitted by opt-in headless Forge tests.

    Reject interleaved test sessions unless --session selects one. No raw
    printed game messages, user names, card names or prompts are retained.
    """
    events = []
    sessions = set()
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if "BUGHUNT_EVENT " not in line:
            continue
        payload = line.split("BUGHUNT_EVENT ", 1)[1].strip()
        try:
            event = json.loads(payload)
        except json.JSONDecodeError as exc:
            raise ValueError(f"line {n}: malformed sanitized trace event") from exc
        if not isinstance(event, dict) or not isinstance(event.get("session"), str):
            raise ValueError(f"line {n}: invalid trace event/session")
        sessions.add(event["session"])
        if session is not None and event["session"] != session:
            continue
        # Strictly reject accidental source data in event payloads.
        safe = {"kind", "at_ms", "session", "version", "turn", "phase", "active",
                "priority", "pending", "legal", "board_revision", "name"}
        if set(event) - safe:
            raise ValueError(f"line {n}: unexpected trace fields: {sorted(set(event) - safe)}")
        events.append(event)
    if session is None and len(sessions) > 1:
        raise ValueError("multiple independent sessions; supply --session with a session name")
    if not events:
        raise ValueError("no matching opt-in BUGHUNT_EVENT entries found")
    return events

def parse_diagnostic(path: Path) -> list[dict]:
    """Minimal parser for locally exported Arcana text logs.

    Intentionally keeps NO card names, deck names, addresses, player IDs or raw
    message text. Generated normalized events may be inspected locally; never
    publish raw owner diagnostics in this public repository.
    """
    events = []
    start = None
    for line in path.read_text(encoding="utf-8").splitlines():
        m = DIAG_RE.match(line)
        if not m:
            continue
        try:
            stamp = datetime.fromisoformat(m.group(1))
        except ValueError:
            continue
        if start is None:
            start = stamp
        at_ms = int((stamp - start).total_seconds() * 1000)
        category, message = m.group(2), m.group(3)
        state = STATE_RE.search(message)
        if category == "Engine" and state:
            events.append({"kind": "state", "at_ms": at_ms, "version": int(state.group(1)),
                           "turn": int(state.group(2)), "phase": state.group(3).replace(" ", "_"),
                           "legal": int(state.group(4))})
        elif category == "Player action":
            if re.search(r"Declared \d+ blocker", message):
                name = "DeclareBlockers"
            elif message.startswith("Sent "):
                name = message.split(" ", 2)[1].split("(")[0]
            elif message.startswith("Answered "):
                name = "SubmitDecision"
            else:
                continue
            events.append({"kind": "action", "at_ms": at_ms, "name": name})
        elif category == "Error":
            events.append({"kind": "error", "at_ms": at_ms})
        elif "Diagnostic export requested" in message:
            events.append({"kind": "tick", "at_ms": at_ms})
    if not events:
        raise ValueError("no usable events in diagnostic")
    return events


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="mode", required=True)
    a = subs.add_parser("audit", help="compare source hazards against the reviewed baseline")
    a.add_argument("--root", type=Path, default=ROOT)
    a.add_argument("--json", action="store_true", help="machine-readable report")
    for name in ("trace", "diagnostic", "live-log"):
        sub = subs.add_parser(name, help=f"check a local {name} for stalled handoffs")
        sub.add_argument("path", type=Path)
        sub.add_argument("--stall-ms", type=int, default=10000)
        if name == "live-log":
            sub.add_argument("--session", type=str, default=None, help="anonymized headless test session")
        sub.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.mode == "audit":
            result = audit(args.root)
        else:
            events = (parse_jsonl(args.path) if args.mode == "trace"
                      else parse_diagnostic(args.path) if args.mode == "diagnostic"
                      else parse_live_log(args.path, args.session))
            result = analyze(events, stall_ms=args.stall_ms)
    except (OSError, ValueError, KeyError) as exc:
        print(f"BUGHUNT INPUT ERROR: {exc}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    elif args.mode == "audit":
        for c in result["checks"]:
            print(f"{c['severity']} {c['status']} {c['id']}: {c['found']} (baseline {c['baseline']})")
        print("BUGHUNT AUDIT " + ("PASS (known risks remain)" if result["passed"] else "FAIL (new source risks)"))
    else:
        print(f"BUGHUNT TRACE: {result['counts']} last state v{result['last_version']}")
        for finding in result["findings"]:
            print(f"{finding['severity']} {finding['code']} at {finding['at_ms']}ms: {finding['detail']}")
        print("BUGHUNT TRACE " + ("PASS (no detected P0)" if result["passed"] else "FAIL (P0 suspect)"))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
