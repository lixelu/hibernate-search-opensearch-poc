"""Measure the MySQL load a workload causes, by differencing global status counters.

    python3 scripts/db-pressure.py snapshot > before.json
    ... run a workload ...
    python3 scripts/db-pressure.py diff before.json "my workload" - 200 write


Counters rather than performance_schema digests: the application uses server-side
prepared statements, whose executions this MySQL build does not record in
events_statements_summary_by_digest. Global status counts every statement and every row
operation regardless of protocol, so the numbers are complete.
"""
import json, subprocess, sys, time

DOCKER = "/Users/alex/.docker/bin/docker"
COUNTERS = ["Com_stmt_execute", "Com_stmt_prepare", "Com_select", "Com_insert", "Com_update",
            "Com_delete", "Com_commit", "Com_begin", "Innodb_rows_read", "Innodb_rows_inserted",
            "Innodb_rows_updated", "Innodb_rows_deleted", "Handler_read_key", "Handler_read_next",
            "Handler_write", "Handler_update", "Handler_delete", "Queries"]

def status():
    names = "','".join(COUNTERS)
    # SHOW GLOBAL STATUS, not performance_schema.global_status: the latter omits the
    # Com_* statement counters entirely, which silently reports zero traffic.
    out = subprocess.run([DOCKER, "exec", "catalog-mysql", "mysql", "-uroot", "-proot", "-N", "-B",
                          "-e", f"SHOW GLOBAL STATUS WHERE Variable_name IN ('{names}');"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(out.stderr[:300])
    return {k: int(v) for k, v in (line.split("\t") for line in out.stdout.strip().splitlines())}

def diff(before, after):
    return {k: after.get(k, 0) - before.get(k, 0) for k in COUNTERS}

def show(title, delta, seconds=None, per=None, per_label="write"):
    print(f"\n=== {title} ===")
    if seconds:
        print(f"    window {seconds:.1f}s"
              + (f"   ({delta['Com_stmt_execute'] / seconds:.1f} statements/s)" if seconds else ""))
    rows = [
        ("statements executed", delta["Com_stmt_execute"] + delta["Com_select"] + delta["Com_insert"]
         + delta["Com_update"] + delta["Com_delete"]),
        ("transactions committed", delta["Com_commit"]),
        ("InnoDB rows read", delta["Innodb_rows_read"]),
        ("InnoDB rows inserted", delta["Innodb_rows_inserted"]),
        ("InnoDB rows updated", delta["Innodb_rows_updated"]),
        ("InnoDB rows deleted", delta["Innodb_rows_deleted"]),
        ("index lookups (Handler_read_key)", delta["Handler_read_key"]),
        ("index scans (Handler_read_next)", delta["Handler_read_next"]),
    ]
    width = max(len(name) for name, _ in rows)
    for name, value in rows:
        line = f"    {name:<{width}}  {value:>12,}"
        if per:
            line += f"   {value / per:>9.1f} per {per_label}"
        print(line)

if __name__ == "__main__":
    if sys.argv[1] == "snapshot":
        print(json.dumps(status()))
    elif sys.argv[1] == "diff":
        def number(index):
            if len(sys.argv) <= index or sys.argv[index] in ("", "-"):
                return None
            return float(sys.argv[index])

        before = json.loads(open(sys.argv[2]).read())
        show(sys.argv[3], diff(before, status()), number(4), number(5),
             sys.argv[6] if len(sys.argv) > 6 else "write")
