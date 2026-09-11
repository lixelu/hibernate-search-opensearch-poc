"""
Reproducer for the finding in docs/opensearch-integration-spec.md section 9.1:
a descending sort on an OpenSearch `scaled_float` field can return documents that
are not the maximum, while a `max` aggregation on the same field reports the true
maximum.

    python3 docs/scaled-float-sort-repro.py http://localhost:9200

Indexes 100k documents per numeric type and compares, for each, the first hit of a
descending sort against the max aggregation (and the ascending sort against min).
Observed on OpenSearch 2.19.6 and 3.8.0: `scaled_float` fails the descending check,
every other numeric type passes. It is data-dependent, so a single run can pass --
run it a few times, or with different value ranges.
"""
import json, random, sys, urllib.request

host = sys.argv[1]

def req(method, path, body=None):
    data = None if body is None else (body if isinstance(body, str) else json.dumps(body)).encode()
    r = urllib.request.Request(f"{host}{path}", data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            return json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return {"error": e.read().decode()[:300]}

def check(idx, mapping, gen, n=100000):
    req("DELETE", f"/{idx}")
    r = req("PUT", f"/{idx}", {"settings": {"index": {"number_of_shards": 1, "number_of_replicas": 0}},
                              "mappings": {"properties": {"id": {"type": "long"}, "rating": mapping}}})
    if "error" in r:
        print(f"  {mapping['type']:<14} create failed: {r['error'][:80]}"); return
    random.seed(7)
    lines = []
    for i in range(1, n + 1):
        lines.append(json.dumps({"index": {"_id": i}}))
        lines.append(json.dumps({"id": i, "rating": gen()}))
        if len(lines) >= 20000:
            req("POST", f"/{idx}/_bulk", "\n".join(lines) + "\n"); lines = []
    if lines:
        req("POST", f"/{idx}/_bulk", "\n".join(lines) + "\n")
    req("POST", f"/{idx}/_refresh")
    mx = req("POST", f"/{idx}/_search", {"size": 0, "aggs": {"m": {"max": {"field": "rating"}}}})["aggregations"]["m"]["value"]
    mn = req("POST", f"/{idx}/_search", {"size": 0, "aggs": {"m": {"min": {"field": "rating"}}}})["aggregations"]["m"]["value"]
    desc = [h["_source"]["rating"] for h in req("POST", f"/{idx}/_search",
            {"size": 1, "sort": [{"rating": {"order": "desc"}}], "_source": ["rating"]})["hits"]["hits"]]
    asc = [h["_source"]["rating"] for h in req("POST", f"/{idx}/_search",
            {"size": 1, "sort": [{"rating": {"order": "asc"}}], "_source": ["rating"]})["hits"]["hits"]]
    dok = desc and abs(desc[0] - mx) < 1e-6
    aok = asc and abs(asc[0] - mn) < 1e-6
    print(f"  {mapping['type']:<14} max={mx:<9} sortDesc={str(desc[0]):<9} {'OK  ' if dok else 'WRONG'}   "
          f"min={mn:<9} sortAsc={str(asc[0]):<9} {'OK' if aok else 'WRONG'}")

print("=== OpenSearch", req("GET", "/")["version"]["number"], "===")
check("m_scaled", {"type": "scaled_float", "scaling_factor": 100}, lambda: round(1 + random.random() * 4, 2))
check("m_float", {"type": "float"}, lambda: round(1 + random.random() * 4, 2))
check("m_double", {"type": "double"}, lambda: round(1 + random.random() * 4, 2))
check("m_halffloat", {"type": "half_float"}, lambda: round(1 + random.random() * 4, 2))
check("m_long", {"type": "long"}, lambda: random.randint(100, 500))
check("m_scaled_price", {"type": "scaled_float", "scaling_factor": 100}, lambda: round(5 + random.random() * 195, 2))
