"""Pretty-print the /api/admin/benchmark report. Usage: python3 scripts/benchmark.py [iterations]"""
import json, sys, urllib.request

iterations = sys.argv[1] if len(sys.argv) > 1 else "15"
report = json.load(urllib.request.urlopen(
    f"http://localhost:8080/api/admin/benchmark?iterations={iterations}", timeout=900))

engines = [c["engine"] for c in report["scenarios"][0]["candidates"]] if report["scenarios"] else []
width = 34
head = f"{'scenario':<{width}}{'mysql p50':>11}"
for e in engines:
    head += f"{e + ' p50':>22}"
print(head)
print("-" * len(head))
for s in report["scenarios"]:
    row = f"{s['scenario']:<{width}}{s['mysql']['p50Millis']:>9}ms"
    for c in s["candidates"]:
        agree = "=" if c["agreesWithMysql"] else "DIFF"
        row += f"{c['timing']['p50Millis']:>9}ms {c['speedup']:>7} {agree:>4}"
    print(row)
print(f"\nhits (mysql): " + ", ".join(f"{s['scenario']}={s['mysql']['totalHits']:,}" for s in report["scenarios"][:3]))
print('"=" means the candidate returned identical ids in identical order to MySQL.')
