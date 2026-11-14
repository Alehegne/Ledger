# Prometheus & Grafana Verification and Operations Guide

This guide provides step-by-step instructions with verified command outputs to operate, query, and validate Prometheus and Grafana integration for the Ledger application.

---

## Overview of Services & Access URLs

| Service | Port / URL | Credentials / Access |
| :--- | :--- | :--- |
| **Frontend Web App** | `http://localhost` (port `80`) | Web Browser |
| **Backend REST API** | `http://localhost:8080` | JWT Bearer Token |
| **Spring Boot Actuator Health** | `http://localhost:8080/actuator/health` | Public summary (`UP`) |
| **Prometheus Web UI** | `http://localhost:9090` | Open access |
| **Prometheus Target Status** | `http://localhost:9090/targets` | Open access |
| **Grafana Web UI** | `http://localhost:3000` | `admin` / `admin` |

---

## Step 1: Verify All Docker Containers are Healthy

Verify that the entire container stack is running and healthy:

```bash
docker compose ps
```

### Example Output:
```text
NAME                  IMAGE                    COMMAND                  SERVICE      CREATED          STATUS                    PORTS
ledger-backend-1      ledger-backend           "sh -c 'exec java $J…"   backend      30 minutes ago   Up 22 minutes (healthy)   0.0.0.0:8080->8080/tcp, [::]:8080->8080/tcp
ledger-db-1           postgres:16              "docker-entrypoint.s…"   db           30 minutes ago   Up 22 minutes (healthy)   0.0.0.0:5433->5432/tcp, [::]:5433->5432/tcp
ledger-frontend-1     ledger-frontend          "/docker-entrypoint.…"   frontend     2 minutes ago    Up 2 minutes (healthy)    0.0.0.0:80->8080/tcp, [::]:80->8080/tcp
ledger-grafana-1      grafana/grafana:11.5.2   "/run.sh"                grafana      12 hours ago     Up 21 minutes (healthy)   0.0.0.0:3000->3000/tcp, [::]:3000->3000/tcp
ledger-kafka-1        apache/kafka:3.8.0       "/__cacert_entrypoin…"   kafka        13 hours ago     Up 22 minutes (healthy)   0.0.0.0:9092->9092/tcp, [::]:9092->9092/tcp
ledger-prometheus-1   prom/prometheus:v3.2.1   "/bin/prometheus --c…"   prometheus   12 hours ago     Up 21 minutes (healthy)   0.0.0.0:9090->9090/tcp, [::]:9090->9090/tcp
```

---

## Step 2: Ensure Prometheus Has a Valid Scrape Token

Because `/actuator/prometheus` is secured by Spring Security to `ROLE_SUPER_ADMIN`, Prometheus reads a Bearer token mounted at `/etc/prometheus/secrets/ledger_bearer_token`.

Generate or refresh the token with the included helper:

```bash
./observability/scripts/refresh-prometheus-token.sh --once
```

### Example Output:
```text
[2026-11-13T14:10:52+03:00] Updated Prometheus Bearer token at /home/alexoo/notes/backend/Ledger/observability/prometheus/secrets/ledger_bearer_token
```

> **Tip:** In long development sessions, run `./observability/scripts/refresh-prometheus-token.sh --watch 600` in a background terminal so the 15-minute access token automatically stays fresh.

---

## Step 3: Check Prometheus Scrape Targets

1. Open your browser and navigate to `http://localhost:9090/targets`.
2. Or query the target status directly via curl:

```bash
curl -fsS http://localhost:9090/api/v1/targets | python3 -c '
import sys, json
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    print(t["labels"].get("job"), "-> health:", t["health"], "| URL:", t["scrapeUrl"])
'
```

### Example Output:
```text
ledger-api -> health: up | URL: http://backend:8080/actuator/prometheus
ledger-api -> health: up | URL: http://host.docker.internal:8080/actuator/prometheus
prometheus -> health: up | URL: http://localhost:9090/metrics
```

In the Prometheus Web UI, the **`ledger-api`** endpoint shows a blue **`UP`** state with green badge and scrape duration:

```text
Endpoint: http://backend:8080/actuator/prometheus
State: UP
Labels: application="Ledger", environment="local", instance="backend:8080", job="ledger-api"
Last Scrape: 2.1s ago
Scrape Duration: 24.3ms
```

---

## Step 4: Run PromQL Queries in Prometheus Web UI

Navigate to `http://localhost:9090/graph`. You can enter any of the following queries in the search bar and click **Execute**:

### Query 1: API Up State
```promql
up{job="ledger-api"}
```
- **Value:** `1` (indicates the Ledger application is answering health and metrics probes).

### Query 2: Financial Reconciliation Invariant Status
```promql
ledger_reconciliation_status
```
- **Value:** `1` (`1` = PASSED, `0` = FAILED, `-1` = UNKNOWN).

### Query 3: Cumulative Posted Journal Entries
```promql
ledger_journal_posted_count_total
```
- **Value:** Current integer count of all committed posted entries since startup.

### Query 4: JVM Heap Memory Used (Bytes)
```promql
sum(jvm_memory_used_bytes{job="ledger-api", area="heap"})
```
- **Value:** e.g., `253227472` (~`241 MB`).

### Terminal Verification Command:
```bash
for q in 'up{job="ledger-api"}' 'ledger_reconciliation_status' 'ledger_journal_posted_count_total' 'hikaricp_connections_idle'; do
  curl -fsSG http://localhost:9090/api/v1/query --data-urlencode "query=$q" | python3 -c '
import sys, json
data = json.load(sys.stdin)["data"]["result"]
if data:
    r = data[0]
    print(f"{r[\"metric\"].get(\"__name__\", \"metric\")}: {r[\"value\"][1]}")
'
done
```

### Example Output:
```text
up: 1
ledger_reconciliation_status: 1
ledger_journal_posted_count_total: 1
hikaricp_connections_idle: 10
```

---

## Step 5: Log into Grafana & Verify Auto-Provisioned Data Source

1. Open `http://localhost:3000` in your browser.
2. Log in with default credentials:
   - **Username:** `admin`
   - **Password:** `admin`
3. Check data source health:
   - In the left sidebar, click **Connections** -> **Data sources**.
   - You will see **Prometheus** (uid: `ledger-prometheus`, URL: `http://prometheus:9090`).
   - Click it, scroll down, and click **Save & test**.

### Terminal API Verification:
```bash
curl -fsS -u admin:admin http://localhost:3000/api/datasources/uid/ledger-prometheus/health
```

### Example Output:
```json
{
  "details": {
    "application": "Prometheus",
    "features": { "rulerApiEnabled": false }
  },
  "message": "Successfully queried the Prometheus API.",
  "status": "OK"
}
```

---

## Step 6: View the Pre-Provisioned Ledger Dashboard

1. In Grafana's left navigation menu, click **Dashboards**.
2. Click the **Ledger** folder.
3. Open **Ledger API — Local Observability**.
4. In the top-right corner:
   - Change the time range to **Last 15 minutes**.
   - Set the auto-refresh interval to **5s**.

### What You Will See on the 15 Dashboard Panels:

```text
+-----------------------+-----------------------+-----------------------+-----------------------+
|  Ledger API Target    | Reconciliation Status | Journal Entries Posted| Journal Entries Rev.  |
|         UP            |        PASSED         |        1 Total        |        0 Total        |
+-----------------------+-----------------------+-----------------------+-----------------------+
|   Journal Post & Reversal Rate Graph          |     Ledger Transaction Duration (p95 / Avg)   |
|   (timeseries rate per minute)                |     (timeseries in seconds from Micrometer)   |
+-----------------------------------------------+-----------------------------------------------+
|   HTTP Request Rate (total & per URI)         |     HTTP 4xx & 5xx Error Rate                 |
|   (timeseries requests/sec)                   |     (timeseries client vs server faults)      |
+-----------------------------------------------+-----------------------------------------------+
|   JVM Memory Usage (Heap used, Max, Non-heap) |     JVM Thread Count (Live, Daemon, Peak)     |
|   (timeseries bytes)                          |     (timeseries active threads)               |
+-----------------------------------------------+-----------------------------------------------+
|   Database Connection Pool (HikariCP)         |     Kafka Template Send Latency               |
|   (active, idle, pending connections)         |     (Outbox event dispatch duration)          |
+-----------------------------------------------+-----------------------------------------------+
```

---

## Step 7: Generate Real Accounting Traffic & Watch Metrics Update

Run this script to authenticate as tenant `admin@ledger.test`, create a draft journal entry, post it with an idempotency key, run continuous reconciliation, and query Prometheus to observe the live metric increments:

```bash
set -e
# 1. Login as tenant Admin
ADMIN_TOKEN=$(curl -fsS -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@ledger.test","password":"Admin123!"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# 2. Pick first two active accounts
ACC1=$(curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/accounts \
  | python3 -c "import sys,json; print([a['id'] for a in json.load(sys.stdin) if a['status']=='ACTIVE'][0])")
ACC2=$(curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/accounts \
  | python3 -c "import sys,json; print([a['id'] for a in json.load(sys.stdin) if a['status']=='ACTIVE'][1])")

# 3. Create Draft Entry
ENTRY_ID=$(curl -fsS -X POST http://localhost:8080/journal-entries \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"transactionDate\":\"2026-11-13\",\"description\":\"Live Demo Entry\",\"reference\":\"DEMO-$(date +%s)\",\"lines\":[{\"accountId\":$ACC1,\"side\":\"DEBIT\",\"amount\":50.00},{\"accountId\":$ACC2,\"side\":\"CREDIT\",\"amount\":50.00}]}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Created Draft Entry ID: $ENTRY_ID"

# 4. Post Entry with Idempotency Key
curl -fsS -X POST "http://localhost:8080/journal-entries/$ENTRY_ID/post" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: demo-post-$(date +%s%N)" >/dev/null
echo "Posted Entry ID: $ENTRY_ID successfully"

# 5. Run Financial Reconciliation
curl -fsS -X POST "http://localhost:8080/api/reconciliation/run" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print(f"Reconciliation Run ID: {d[\"id\"]} | Status: {d[\"status\"]} | Variance: {d[\"globalVariance\"]}")
'
```

### Example Output:
```text
Created Draft Entry ID: 6
Posted Entry ID: 6 successfully
Reconciliation Run ID: 6 | Status: PASSED | Variance: 0.0
```

Wait 5–10 seconds for the next Prometheus scrape, and in your Grafana browser window at `http://localhost:3000`:
1. The **Journal Entries Posted (Cumulative)** stat panel increments by `+1`.
2. The **Journal Entries Posted Rate** timeseries panel spikes up to reflect the post.
3. The **HTTP Request Rate** graph shows new requests for `/journal-entries` and `/api/reconciliation/run`.
4. The **Reconciliation Status** stat panel remains green (`PASSED`).

---

## Step 8: Verify Failure Detection & Automatic Recovery

You can confirm that Prometheus and Grafana detect an outage and recover automatically:

### 1. Stop the Backend Container
```bash
docker compose stop backend
```
Wait 6–8 seconds and check Prometheus targets:
```bash
curl -fsS http://localhost:9090/api/v1/query --data-urlencode 'query=up{job="ledger-api"}' | python3 -c '
import sys, json
for r in json.load(sys.stdin)["data"]["result"]:
    print("Instance:", r["metric"].get("instance"), "-> UP state:", r["value"][1])
'
```

### Example Output:
```text
Instance: backend:8080 -> UP state: 0
```
In Grafana, the **Ledger API Target** panel immediately turns **RED** with `DOWN`.

### 2. Restart the Backend Container
```bash
docker compose start backend
```
Wait 10–15 seconds for Spring Boot to start and Prometheus to perform the next scrape:
```bash
curl -fsS http://localhost:9090/api/v1/query --data-urlencode 'query=up{job="ledger-api"}' | python3 -c '
import sys, json
for r in json.load(sys.stdin)["data"]["result"]:
    print("Instance:", r["metric"].get("instance"), "-> UP state:", r["value"][1])
'
```

### Example Output:
```text
Instance: backend:8080 -> UP state: 1
```
In Grafana, the **Ledger API Target** panel switches back to **GREEN** with `UP`.
