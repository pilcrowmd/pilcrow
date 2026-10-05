# Incident note: ledger-gateway latency spike

**Status:** resolved. **Duration:** 41 minutes. **Impact:** about one in twelve requests took longer than 5 seconds.

The gateway began timing out after a configuration change doubled the size of its database connection pool. This note records what happened and how to roll back safely.

## Service settings at the time

```yaml
service: ledger-gateway
environment: production
replicas: 6
database:
  host: db-primary.internal.example.org
  pool_size: 80
  timeout_seconds: 5
health_check:
  path: /healthz
  interval_seconds: 10
```

The earlier value of `pool_size` was 40. The database could not hold 480 connections from six replicas.

> [!WARNING]
> Do not restart all replicas at once. Roll them one at a time, or the pool resets everywhere and the database receives every reconnect in the same second.

## Timeline

| Time (UTC) | Event |
| ---------- | ----- |
| 09:02 | Configuration deployed |
| 09:11 | First timeout alert fires |
| 09:20 | On-call lead starts investigation |
| 09:34 | Pool size identified as the cause |
| 09:43 | Rollback finished, latency normal |

## Rollback steps

1. Open the configuration file and set `pool_size` back to 40.
2. Deploy the change to a single replica and watch its error rate for two minutes.
3. Continue with the other five replicas, one at a time.
4. Confirm that `/healthz` returns 200 on every replica.
5. Post a short update in the incident channel and close the alert.

## Follow-up actions

- Add a limit check so the total pool across replicas cannot exceed the database maximum.
- Lower the first alert threshold from 10 minutes to 3.
- Review this runbook with the next on-call group.
