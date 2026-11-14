# Local Observability & Monitoring Guide (Prometheus + Grafana)

This guide covers running, querying, visualizing, and troubleshooting local observability for the **Ledger** backend using **Spring Boot Actuator / Micrometer**, **Prometheus**, and **Grafana** via Docker Compose.

---

## A. Architecture

```text
+-----------------------------------+       Bearer JWT (SUPER_ADMIN)        +----------------------------------+
| Ledger Spring Boot API (Host)     | <-------------------------------------- | Prometheus (Docker :9090)        |
|  - Micrometer Registry            |   GET host.docker.internal:8080         |  - Scrapes every 5s              |
|  - /actuator/health               |       /actuator/prometheus              |  - TSDB volume: prometheus_data  |
|  - /actuator/prometheus (secured) |                                         +----------------------------------+
+-----------------------------------+                                                          ^
                                                                                               | PromQL (HTTP :9090)
                                                                              +----------------------------------+
                                                                              | Grafana (Docker :3000)           |
                                                                              |  - Provisioned Datasource        |
                                                                              |  - Ledger Dashboard              |
                                                                              |  - Volume: grafana_data          |
                                                                              +----------------------------------+
```

### How the Components Communicate

1. **Spring Boot + Actuator + Micrometer (Host `:8080`)**:
   - Exposes `/actuator/health` (public status, detailed component health when authenticated as `SUPER_ADMIN`) and `/actuator/prometheus` (restricted to `ROLE_SUPER_ADMIN` via Spring Security).
   - Exports JVM, Tomcat, HTTP server, HikariCP/JDBC, Logback, Spring Kafka template, and custom commit-safe Ledger metrics (`LedgerMetricsService`).
2. **Prometheus (`prom/prometheus:v3.2.1`, Container `:9090`)**:
   - Runs inside Docker Compose and scrapes `http://host.docker.internal:8080/actuator/prometheus` every **5 seconds** (`job_name: ledger-api` in `observability/prometheus/prometheus.yml`).
   - Authenticates using a `SUPER_ADMIN` JWT Bearer token stored locally in `observability/prometheus/secrets/ledger_bearer_token` (gitignored, mounted read-only at `/etc/prometheus/secrets/ledger_bearer_token`). Prometheus re-reads this file automatically on each scrape.
   - Persists time-series metrics in the `prometheus_data` Docker volume (7-day retention).
3. **Grafana (`grafana/grafana:11.5.2`, Container `:3000`)**:
   - Auto-provisions the Prometheus datasource (`http://prometheus:9090` over the internal Docker Compose network) via `observability/grafana/provisioning/datasources/prometheus.yml`.
   - Auto-loads the **Ledger API — Local Observability** dashboard via `observability/grafana/provisioning/dashboards/dashboards.yml` and `observability/grafana/dashboards/ledger-observability.json`.
   - Persists Grafana state (users, saved edits, preferences) in the `grafana_data` Docker volume.

---

## B. Starting and Stopping

### 1. Start Prerequisites & Ledger Application

Start PostgreSQL and Kafka if not already running, then start the Ledger Spring Boot application on port `8080`:

```bash
docker compose up -d db kafka
./mvnw spring-boot:run
```

### 2. Fetch the Prometheus Scrape JWT Token

Once Ledger is up, generate the local `SUPER_ADMIN` Bearer token file for Prometheus (gitignored):

```bash
./observability/scripts/refresh-prometheus-token.sh --once
```

Because local JWT access tokens expire after 15 minutes (`ledger.jwt.expiration-ms=900000`), you can optionally run the helper in watch mode in a dedicated terminal to auto-refresh every 10 minutes:

```bash
./observability/scripts/refresh-prometheus-token.sh --watch 600
```

> **Note:** Override `LEDGER_SUPER_ADMIN_EMAIL`, `LEDGER_SUPER_ADMIN_PASSWORD`, or `LEDGER_BASE_URL` via environment variables if you changed the local bootstrap defaults. Never commit real/production credentials or tokens.

### 3. Start Prometheus & Grafana

```bash
docker compose up -d prometheus grafana
```

### 4. Check Container Status & Logs

```bash
# Check status and health checks
docker compose ps prometheus grafana

# Follow logs
docker compose logs -f prometheus grafana
```

### 5. Stop or Restart Monitoring Services

```bash
# Stop only Prometheus and Grafana (keeps volumes and containers intact)
docker compose stop prometheus grafana

# Restart Prometheus and Grafana
docker compose restart prometheus grafana
```

### 6. Remove Monitoring Containers Without Deleting Monitoring Data

```bash
# Stop and remove only the monitoring containers while keeping prometheus_data and grafana_data volumes
docker compose rm -s -f prometheus grafana
```

### 7. Completely Reset Monitoring Data (Explicit Reset Only)

> **Warning:** Do **not** run `docker compose down -v` unless you also want to delete `db_data` and `kafka_data`! To reset **only** monitoring data, stop/remove only the monitoring containers and delete only the two monitoring volumes:

```bash
docker compose rm -s -f prometheus grafana
docker volume rm ledger_prometheus_data ledger_grafana_data
```

*(Verify exact volume names with `docker volume ls | grep -E 'prometheus_data|grafana_data'` if your Compose project directory name differs.)*

---

## C. Access URLs

| Service / Endpoint | Local URL | Access / Auth Notes |
| :--- | :--- | :--- |
| **Spring Boot Health** | `http://localhost:8080/actuator/health` | Public summary (`{"status":"UP"}`); pass `SUPER_ADMIN` Bearer token for `components` (`db`, `diskSpace`, probes). |
| **Spring Boot Prometheus Metrics** | `http://localhost:8080/actuator/prometheus` | Requires `Authorization: Bearer <SUPER_ADMIN_JWT>`. Anonymous -> `401`, Tenant user -> `403`. |
| **Prometheus UI** | `http://localhost:9090` | Open local web UI for ad-hoc PromQL queries and graphing. |
| **Prometheus Scrape Targets** | `http://localhost:9090/targets` | Shows `ledger-api` and `prometheus` scrape state (`UP`/`DOWN`, last scrape duration, errors). |
| **Grafana UI** | `http://localhost:3000` | Default local dev login: `admin` / `admin` (override with `GRAFANA_ADMIN_USER` & `GRAFANA_ADMIN_PASSWORD`). |

---

## D. Grafana Usage

### Accessing Grafana & Locating the Provisioned Data Source

1. Open `http://localhost:3000` in your browser.
2. Sign in with `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` (default: `admin` / `admin`).
3. Click **Connections -> Data sources** in the left navigation menu.
4. Select **Prometheus** (marked **default**, pointing to `http://prometheus:9090`). Click **Save & test** at the bottom to confirm *"Successfully queried the Prometheus API."*

### Opening the Provisioned Ledger Dashboard

1. Click **Dashboards** in the left sidebar.
2. Expand the **Ledger** folder.
3. Click **Ledger API — Local Observability**.

### Creating a New Dashboard & Adding a Panel

1. Click **Dashboards** -> **New** -> **New dashboard**.
2. Click **+ Add visualization**.
3. Select the **Prometheus** data source.
4. In the **Query** editor (switch from **Builder** to **Code** mode if desired), enter a PromQL expression such as:
   ```promql
   sum(rate(http_server_requests_seconds_count{job="ledger-api"}[1m]))
   ```
5. Click **Run queries** to preview live data, configure the panel **Title** and **Standard options -> Unit** on the right pane, then click **Apply**.

### Changing Time Range & Refresh Interval

- Use the top-right **Time picker** (e.g., `Last 15 minutes`, `Last 30 minutes`, `Last 1 hour`).
- Click the **Refresh** dropdown next to it (e.g., `5s`, `10s`, `30s`) for near-real-time updates during local testing.

### Saving and Exporting Dashboards

- Click the **Save dashboard** icon (top-right) to store changes in Grafana's `grafana_data` volume.
- To export a dashboard to version control (or update `observability/grafana/dashboards/ledger-observability.json`), open the dashboard -> click **Share** (or **Dashboard settings -> JSON Model**) -> **Export -> Save to file**.

---

## E. PromQL Examples (Verified Against Exposed Ledger Metrics)

> **Note on metric availability:**
> - **Histogram vs. Summary:** `ledger_transaction_duration_seconds` is configured with `.publishPercentileHistogram()`, so `_bucket` and `histogram_quantile(...)` work. Standard Spring MVC `http_server_requests_seconds` is exported as a Micrometer summary (`_count`, `_sum`, `_max` without `_bucket`), so average and max latency are queried directly rather than percentiles.
> - **Kafka & Outbox metrics:** `spring_kafka_template_seconds_*` metrics are exposed once an outbox message is sent via `KafkaTemplate`. Custom `ledger_outbox_*` pending/retry/dead-letter gauges are **not** currently implemented in the application.

### 1. HTTP Requests per Second
```promql
sum(rate(http_server_requests_seconds_count{job="ledger-api"}[1m]))
```
Measures total incoming HTTP request throughput across all endpoints. Group by endpoint with:
```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{job="ledger-api", uri!~"/actuator.*"}[1m]))
```

### 2. HTTP 4xx and 5xx Error Rate
```promql
sum(rate(http_server_requests_seconds_count{job="ledger-api", status=~"4.."}[1m]))
sum(rate(http_server_requests_seconds_count{job="ledger-api", status=~"5.."}[1m]))
```
Tracks client validation/auth rejections (`4xx`) and server faults (`5xx`) per second.

### 3. HTTP Average & Max Latency (Seconds)
```promql
sum(rate(http_server_requests_seconds_sum{job="ledger-api"}[1m]))
/
sum(rate(http_server_requests_seconds_count{job="ledger-api"}[1m]))
```
```promql
max(http_server_requests_seconds_max{job="ledger-api"})
```
Calculates rolling average request duration and peak observed latency in seconds.

### 4. JVM Heap & Non-Heap Memory Usage (Bytes)
```promql
sum(jvm_memory_used_bytes{job="ledger-api", area="heap"})
sum(jvm_memory_max_bytes{job="ledger-api", area="heap"})
```
Tracks current heap consumption against the JVM heap limit (`-Xmx`).

### 5. JVM Thread Count
```promql
jvm_threads_live_threads{job="ledger-api"}
```
Monitors active threads to spot thread leaks, executor saturation, or lock contention.

### 6. HikariCP Database Connection Pool Usage
```promql
hikaricp_connections_active{job="ledger-api"}
hikaricp_connections_pending{job="ledger-api"}
```
Shows active SQL connections and threads waiting for a DB connection (`pending > 0` signals pool contention or slow transactions).

### 7. Journal Posting Counters
```promql
ledger_journal_posted_count_total{job="ledger-api"}
rate(ledger_journal_posted_count_total{job="ledger-api"}[5m]) * 60
```
Cumulative committed journal posts since app start, and committed posts per minute.

### 8. Journal Reversal Counters
```promql
ledger_journal_reversed_count_total{job="ledger-api"}
rate(ledger_journal_reversed_count_total{job="ledger-api"}[5m]) * 60
```
Cumulative committed journal reversals since app start, and reversals per minute.

### 9. Transaction Duration (`ledger_transaction_duration_seconds`)
```promql
# p95 transaction latency (seconds)
histogram_quantile(0.95, sum(rate(ledger_transaction_duration_seconds_bucket{job="ledger-api"}[5m])) by (le))

# Rolling average transaction latency (seconds)
sum(rate(ledger_transaction_duration_seconds_sum{job="ledger-api"}[5m]))
/
sum(rate(ledger_transaction_duration_seconds_count{job="ledger-api"}[5m]))
```
Measures end-to-end execution duration for posting, reversing, and reconciling entries.

### 10. Reconciliation Status
```promql
ledger_reconciliation_status{job="ledger-api"}
```
Current financial reconciliation integrity gauge: `1` = `PASSED`, `0` = `FAILED`, `-1` = `UNKNOWN` (no run recorded yet).

---

## F. Troubleshooting

| Symptom | Likely Cause | How to Verify & Fix |
| :--- | :--- | :--- |
| **Prometheus target shows `DOWN` (`connection refused`)** | Ledger backend is not running on port `8080`, or bound only to `127.0.0.1` instead of `*:8080`. | Confirm `./mvnw spring-boot:run` is running (`curl http://localhost:8080/actuator/health`). Check `ss -tlnp \| grep 8080`. |
| **Prometheus gets `401 Unauthorized` or `403 Forbidden`** | `observability/prometheus/secrets/ledger_bearer_token` is missing, expired (>15m old), unreadable by container UID `65534`, or belongs to a non-`SUPER_ADMIN` user. | Re-run `./observability/scripts/refresh-prometheus-token.sh --once` (or `--watch 600`) and ensure file mode is `644`. |
| **Prometheus logs error reading `credentials_file`** | `ledger_bearer_token` file was not generated before starting Prometheus. | Run `./observability/scripts/refresh-prometheus-token.sh --once`, then check `http://localhost:9090/targets`. |
| **Docker networking error (`no such host host.docker.internal`)** | Running native Linux Docker Engine without `host-gateway` mapping. | `docker-compose.yml` already sets `extra_hosts: ["host.docker.internal:host-gateway"]`. Recreate with `docker compose up -d --force-recreate prometheus`. |
| **Grafana cannot connect to Prometheus** | `prometheus` container is stopped/unhealthy or datasource URL points to `localhost:9090` instead of `http://prometheus:9090`. | Run `docker compose ps`, verify `http://prometheus:9090` in Grafana datasource settings (remember `localhost` inside the Grafana container refers to Grafana itself, not Prometheus). |
| **Missing metrics (`spring_kafka_template_seconds_*`, `ledger_transaction_duration_seconds_*`)** | Some Micrometer meters are only created lazily on first invocation (e.g., first journal post/reverse or Kafka publish). Also note custom `ledger_outbox_*` gauges are not implemented. | Trigger a journal post/reconciliation call, wait 5–10 seconds for the next scrape, and re-run the query. |
| **Empty Grafana dashboard panels** | Time range is wrong, rate window `[5m]` has <2 scrapes yet, or no traffic has happened. | Set dashboard time range to **Last 15 minutes**, wait 10–15 seconds (2+ scrapes at 5s interval), and execute a couple of API requests. |
| **Spring Boot runs on a different port (e.g., `8081`)** | `SERVER_PORT` or `--server.port` was changed locally. | Update `targets: ["host.docker.internal:8081"]` in `observability/prometheus/prometheus.yml`, set `LEDGER_BASE_URL=http://localhost:8081` when refreshing the token, and reload Prometheus (`curl -X POST http://localhost:9090/-/reload`). |

---

## G. Metric Interpretation

- **`ledger_reconciliation_status` drops to `0` (`FAILED`)**:
  - Indicates the most recent financial reconciliation run detected a double-entry violation, trial balance / general ledger mismatch, or circular account hierarchy. Investigate `/api/reconciliation/history` and recent audit logs immediately.
- **`ledger_journal_reversed_count_total` spikes relative to `ledger_journal_posted_count_total`**:
  - High reversal ratios can signal upstream client integration retries bypassing idempotency keys, user workflow confusion, or faulty automated posting jobs.
- **`ledger_transaction_duration_seconds` p95 / max climbs**:
  - Because `postEntry`, `reverseEntry`, and `runReconciliation` hold database locks (`SELECT ... FOR UPDATE`) or scan ledger lines, rising duration warns of lock contention, large reconciliation scans, or slow PostgreSQL queries before timeouts occur.
- **`hikaricp_connections_pending > 0` or `hikaricp_connections_active == hikaricp_connections_max`**:
  - Connection pool exhaustion. Concurrent requests are waiting on database connections, which will cascade into elevated HTTP latency and request failures.
- **Sustained HTTP `4xx` / `5xx` spikes**:
  - `401`/`403` bursts indicate expired tokens or RBAC misconfigurations; `409`/`422` bursts indicate concurrency/validation conflicts; any `5xx` indicates unhandled backend or database/Kafka availability errors (cross-check structured JSON logs via the response `X-Trace-Id` header / MDC `traceId`).
- **JVM Heap approaching `jvm_memory_max_bytes` or elevated `process_cpu_usage`**:
  - Memory pressure (frequent GC pauses, oversized report/reconciliation payloads) or CPU saturation under load.
