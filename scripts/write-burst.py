"""A fixed write workload, so two configurations can be compared statement for statement.

Each cycle is five mutations: create, update, reprice a variant, adjust stock, delete.
After the burst it waits for both indexing pipelines to drain, so the measurement window
contains the full cost of indexing those writes rather than only the part that finished
before the snapshot was taken.
"""
import json, sys, time, urllib.request

BASE = "http://localhost:8080"

def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    raw = urllib.request.urlopen(r, timeout=120).read()
    return json.loads(raw) if raw else None

def drained():
    try:
        outbox = call("GET", "/api/admin/outbox")["backlog"]
        hs = call("GET", "/api/admin/hs/status")["outboxBacklog"]
        return outbox == 0 and hs == 0
    except Exception:
        return True

cycles = int(sys.argv[1])
tag = sys.argv[2]
started = time.time()
for i in range(cycles):
    sku = f"BURST-{tag}-{i}"
    pid = call("POST", "/api/v1/products", {
        "sku": sku, "name": f"Burst {sku} cotton Shirt", "description": "load probe",
        "brand": "BurstBrand", "status": "ACTIVE", "vendorId": 1, "categoryId": 3, "currency": "EUR",
        "variants": [{"sku": sku + "-v1", "color": "black", "size": "M", "weightGrams": 200,
                      "price": 30.0, "stock": [{"warehouseCode": "EU-WH1", "region": "EU",
                                                "quantity": 5}]}],
        "attributes": {"material": "cotton"}, "tags": ["new"]})["id"]
    variant = call("GET", f"/api/v1/products/{pid}")["variants"][0]["id"]
    call("PUT", f"/api/v1/products/{pid}", {"status": "ACTIVE", "name": f"Burst {sku} renamed"})
    call("PUT", f"/api/v1/products/{pid}/variants/{variant}/price", {"price": 21.5})
    call("PUT", f"/api/v1/products/{pid}/variants/{variant}/stock",
         {"warehouseCode": "EU-WH1", "quantity": 3})
    call("DELETE", f"/api/v1/products/{pid}")

writes = cycles * 5
elapsed = time.time() - started
deadline = time.time() + 120
while not drained() and time.time() < deadline:
    time.sleep(0.2)
print(f"{writes} writes in {elapsed:.1f}s; pipelines drained after a further "
      f"{time.time() - started - elapsed:.1f}s")
