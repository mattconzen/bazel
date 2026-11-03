#!/usr/bin/env python3
"""Sample host process PSS around a workload; never treat RSS as fleet memory."""

import argparse
import json
import math
import platform
import subprocess
import time
from pathlib import Path

FIELDS = {
    "Pss": "pss_bytes",
    "Rss": "rss_bytes",
    "Private_Clean": "private_clean_bytes",
    "Private_Dirty": "private_dirty_bytes",
    "Shared_Clean": "shared_clean_bytes",
    "Shared_Dirty": "shared_dirty_bytes",
    "SwapPss": "swap_pss_bytes",
    "Anonymous": "anonymous_bytes",
    "AnonHugePages": "anonymous_hugepage_bytes",
}
REQUIRED = {"Pss", "Rss", "Private_Clean", "Private_Dirty", "Shared_Clean", "Shared_Dirty"}


def start_ticks(stat):
    # comm may contain spaces and parentheses. Fields after its last ')' start at field 3.
    return int(stat.rsplit(")", 1)[1].split()[19])


def rollup(text):
    values = {}
    present = set()
    for line in text.splitlines():
        key, separator, value = line.partition(":")
        if not separator or key not in FIELDS:
            continue
        number, unit = value.split()
        if unit != "kB" or int(number) < 0:
            raise ValueError(f"Invalid {key} in smaps_rollup")
        values[FIELDS[key]] = int(number) * 1024
        present.add(key)
    missing = REQUIRED - present
    if missing:
        raise ValueError(f"Missing memory fields: {sorted(missing)}")
    return values


def sample(identities, proc=Path("/proc")):
    rows = []
    totals = {}
    complete = True
    for pid, expected_start in identities.items():
        row = {"pid": pid, "start_ticks": expected_start}
        try:
            path = proc / str(pid)
            before = start_ticks((path / "stat").read_text())
            if before != expected_start:
                raise ValueError("PID was reused")
            memory = rollup((path / "smaps_rollup").read_text())
            after = start_ticks((path / "stat").read_text())
            if after != before:
                raise ValueError("PID changed while sampling")
            row["memory"] = memory
            for key, value in memory.items():
                totals[key] = totals.get(key, 0) + value
        except (OSError, ValueError, IndexError) as error:
            complete = False
            row["error"] = str(error)
        rows.append(row)
    return {
        "complete": complete,
        "processes": rows,
        "aggregate": totals if complete else None,
        "observed_partial_totals": totals if not complete else None,
    }


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be positive and finite")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pid", type=int, action="append", required=True,
                        help="Participating host PID; repeat for every VMM/required helper")
    parser.add_argument("--arm", choices=("warmed-control", "patched-off", "heap", "mapped"), required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--workload", required=True)
    parser.add_argument("--restore-backend", required=True)
    parser.add_argument("--clone-count", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New JSONL file (never overwritten)")
    parser.add_argument("--interval", type=positive, default=1.0)
    parser.add_argument("--duration", type=positive,
                        help="Seconds to sample when no workload command is supplied")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if args.clone_count < 1 or any(pid < 1 for pid in args.pid):
        parser.error("PIDs and clone count must be positive")
    if len(args.pid) != len(set(args.pid)):
        parser.error("Repeated PIDs would double-count physical memory")
    if bool(command) == bool(args.duration):
        parser.error("Supply either a workload command after -- or --duration")
    identities = {pid: start_ticks((Path("/proc") / str(pid) / "stat").read_text()) for pid in args.pid}
    boot_id = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    child = None
    failed_samples = 0
    with args.output.open("x") as output:
        def emit(record):
            output.write(json.dumps(record, sort_keys=True) + "\n")
            output.flush()

        emit({"type": "metadata", "schema_version": 1, "arm": args.arm,
              "revision": args.revision, "workload": args.workload,
              "restore_backend": args.restore_backend, "clone_count": args.clone_count,
              "boot_id": boot_id, "kernel": platform.release(), "process_start_ticks": identities,
              "unrepresented_backing_file_cache": "unmeasured",
              "ksm_run": Path("/sys/kernel/mm/ksm/run").read_text().strip()
              if Path("/sys/kernel/mm/ksm/run").exists() else None,
              "command": command, "wall_time_ns": time.time_ns()})
        started = time.monotonic()
        try:
            if command:
                child = subprocess.Popen(command)  # No shell interpretation.
            while True:
                reading = sample(identities)
                failed_samples += not reading["complete"]
                emit({"type": "sample", "elapsed_seconds": time.monotonic() - started,
                      "wall_time_ns": time.time_ns(), **reading})
                if child is not None:
                    if child.poll() is not None:
                        break
                elif time.monotonic() - started >= args.duration:
                    break
                time.sleep(args.interval)
            code = child.returncode if child is not None else 0
            emit({"type": "result", "elapsed_seconds": time.monotonic() - started,
                  "command_exit_code": code, "incomplete_samples": failed_samples,
                  "complete": failed_samples == 0})
            return (128 - code if code < 0 else code) if code else (3 if failed_samples else 0)
        finally:
            if child is not None and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()


if __name__ == "__main__":
    raise SystemExit(main())
