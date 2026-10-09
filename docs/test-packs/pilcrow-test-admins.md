---
title: PilcrowMD test pack – Admins
service: tide-gauge-api
environment: staging
maintainers:
  - ops-team
  - on-call
reviewed: 2026-10-02
---

# PilcrowMD test pack – Admins

**What this is.** The kind of file a system administrator keeps: a runbook with configuration
snippets, logs and a front-matter header. Everything here is made up – the hosts, addresses and
keys do not exist.

**What to look for**

- The card at the top is the front matter. Its list (`maintainers`) should keep its two items.
- Every snippet must be **exactly** as written: same indentation, same blank lines, no characters
  lost. Tap **Copy** on a block and paste it into another app to check.
- Long log lines scroll sideways, or wrap with **Settings → Wrap long lines in code blocks**.
- Most blocks are coloured: YAML, TOML, INI, Dockerfile, shell, PowerShell, batch, JSON, SQL,
  Makefile, HTTP and CSV. The `text`, `nginx` and `log` blocks show as plain, readable monospace.
- Search for *ERROR*. It appears in the log section.

**Known gaps in version 1.0.13** – we know about these, no need to report them:

- The headings drawer lists the front-matter lines as if they were headings.
- A web address written without angle brackets is not a link.
- Opening very large log files: see the Big file pack.

Found something not on this list? Please open an issue at
<https://github.com/pilcrowmd/pilcrow/issues> and say which pack and which section.

---

## 1. Service summary

| Item          | Value                         |
| ------------- | ----------------------------- |
| Service       | `tide-gauge-api`              |
| Hosts         | `gauge-01`, `gauge-02`        |
| Port          | `8443`                        |
| Health check  | `GET /healthz` → `200 OK`     |
| Config path   | `/etc/tide-gauge/config.toml` |
| Logs          | `/var/log/tide-gauge/`        |

## 2. YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tide-gauge-api
  labels:
    app: tide-gauge
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: api
          image: registry.example.org/tide-gauge-api:1.4.2
          ports:
            - containerPort: 8443
```

The same text as ` ```yml ` (not coloured yet, indentation kept):

```yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tide-gauge-api
spec:
  replicas: 2
```

## 3. TOML

```toml
[server]
host = "0.0.0.0"
port = 8443
read_timeout = "15s"

[database]
url = "postgres://gauge@db.example.org:5432/tides"
pool_size = 10

[[alerts]]
name = "low_battery"
threshold = 3.4
```

## 4. INI

```ini
; legacy collector settings
[collector]
interval_seconds = 60
retries = 3

[paths]
spool = /var/spool/tide-gauge
```

## 5. Environment file

```text
TIDE_ENV=staging
TIDE_LOG_LEVEL=info
TIDE_API_KEY=example-not-a-real-key-0000
```

## 6. Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /srv
COPY build/libs/tide-gauge-api.jar app.jar
EXPOSE 8443
HEALTHCHECK --interval=30s CMD curl -fsS https://localhost:8443/healthz || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 7. systemd unit

```ini
[Unit]
Description=Tide gauge API
After=network-online.target

[Service]
User=tide
ExecStart=/usr/bin/java -jar /srv/tide-gauge-api.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## 8. nginx

```nginx
server {
    listen 443 ssl;
    server_name tides.example.org;

    location / {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header Host $host;
    }
}
```

## 9. Shell

```bash
#!/usr/bin/env bash
set -euo pipefail
for host in gauge-01 gauge-02; do
  ssh "$host" 'systemctl is-active tide-gauge && df -h /var/log | tail -1'
done
```

```sh
# crontab: rotate logs every night at 02:30
30 2 * * * /usr/sbin/logrotate /etc/logrotate.d/tide-gauge
```

## 10. PowerShell and batch

```powershell
Get-Service -Name TideGauge | Restart-Service -PassThru
Get-Content C:\TideGauge\logs\app.log -Tail 20
```

```bat
@echo off
net stop TideGauge
net start TideGauge
```

## 11. JSON (coloured)

```json
{
  "status": "ok",
  "uptime_s": 86400,
  "checks": { "database": "ok", "disk": "warn", "queue_depth": 3 }
}
```

## 12. SQL and Makefile (coloured)

```sql
CREATE INDEX IF NOT EXISTS idx_readings_time ON readings (observed_at);
DELETE FROM readings WHERE observed_at < now() - INTERVAL '90 days';
```

```makefile
deploy:
	./gradlew build
	scp build/libs/tide-gauge-api.jar gauge-01:/srv/
```

## 13. HTTP request

```http
GET /v1/harbours/gull-bay/tides?date=2026-10-02 HTTP/1.1
Host: tides.example.org
Accept: application/json
Authorization: Bearer example-token
```

## 14. Logs

```log
2026-10-02T06:00:01Z INFO  api       started on :8443 (pid 4121)
2026-10-02T06:00:02Z INFO  db        pool ready size=10
2026-10-02T06:14:37Z WARN  collector gauge-02 battery=3.45V below soft limit 3.50V
2026-10-02T06:15:00Z ERROR collector gauge-02 timeout after 15000ms request_id=7f3c2a1e-0b9d-4c55-a1e2-9d8f6b4c3a21 retry=1/3 upstream=https://gauge-02.example.org/v1/reading
2026-10-02T06:15:16Z INFO  collector gauge-02 recovered after retry 2/3
```

A single log line that is very long on purpose:

```text
2026-10-02T06:15:00.123456Z level=error service=tide-gauge-api host=gauge-02 trace_id=00f067aa0ba902b7 span_id=53995c3f42cd8ad8 msg="upstream read failed" error="context deadline exceeded (Client.Timeout exceeded while awaiting headers)" path=/v1/reading attempt=1 max_attempts=3 elapsed_ms=15000
```

## 15. CSV

```csv
hour,harbour,height_m,type
03,Gull Bay,0.4,low
09,Gull Bay,4.1,high
15,Gull Bay,0.6,low
```

## 16. Checklist

- [x] Rotate the API key
- [x] Confirm both gauges report
- [ ] Replace the gauge-02 battery
- [ ] Update this runbook

> [!WARNING]
> Never restart both hosts at once: the load balancer needs one healthy host.

---

**End of the Admins pack.** This file is public domain (CC0 1.0). Copy, change and share it freely.
