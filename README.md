# Replicated Log — Iteration 1

Monorepo with homeworks for distributed systems. This iteration implements a
replicated log with **1 master + 2 secondaries**, communicating over REST
with blocking (synchronous) replication.

## Architecture

```
                 POST /messages                GET /messages
 client  ────────────────────────►  master  ◄────────────────────────
                                       │  │
                        POST /replicate│  │POST /replicate
                                       ▼  ▼
                              secondary1  secondary2
```

- **Master** (`master/`): `POST /messages` assigns an id + timestamp, appends
  the message to its own in-memory log, then replicates it to every
  secondary **in parallel** and blocks until **all** of them ack before
  responding to the client. `GET /messages` returns the master's full log.
- **Secondary** (`secondary/`): `POST /replicate` is the internal endpoint
  the master calls; each secondary can be configured to sleep for an
  artificial delay before acking, which makes the master's blocking
  replication observable. `GET /messages` returns everything replicated so
  far.
- **common** (`common/`): the shared `Message` model, JSON (de)serialization,
  and the small `HttpSupport`/`MessageStore` helpers used by both services.

No web framework is used — both servers are built on the JDK's own
`com.sun.net.httpserver.HttpServer`, and master→secondary calls use the
JDK's `java.net.http.HttpClient`. This iteration assumes a **perfect link**
(no retries or failure handling for dropped/failed RPCs).

## Configuration

| Service   | Env var                | Default | Meaning                                              |
|-----------|-------------------------|---------|-------------------------------------------------------|
| master    | `PORT`                  | `8080`  | Port the master listens on                            |
| master    | `SECONDARY_URLS`        | *(none)*| Comma-separated base URLs of the secondaries           |
| secondary | `PORT`                  | `8080`  | Port the secondary listens on                          |
| secondary | `REPLICATION_DELAY_MS`  | `0`     | Artificial delay before acking a replicated message     |

## Running locally (no Docker)

Requires JDK 25+ and Maven.

Configuration is read from environment variables (`System.getenv()`), not
`-D` flags. Bash:

```bash
mvn -q package

PORT=8081 java -jar secondary/target/secondary.jar &
PORT=8082 REPLICATION_DELAY_MS=5000 java -jar secondary/target/secondary.jar &
PORT=8080 SECONDARY_URLS="http://localhost:8081,http://localhost:8082" \
  java -jar master/target/master.jar &
```

PowerShell (one terminal per service):

```powershell
$env:PORT="8081"; java -jar secondary/target/secondary.jar
$env:PORT="8082"; $env:REPLICATION_DELAY_MS="5000"; java -jar secondary/target/secondary.jar
$env:PORT="8080"; $env:SECONDARY_URLS="http://localhost:8081,http://localhost:8082"; java -jar master/target/master.jar
```

## Running with Docker Compose

```bash
docker compose up --build
```

This starts `master` (host port 8080), `secondary1` (host port 8081, no
delay) and `secondary2` (host port 8082, 5s artificial delay).

## Trying it out / verifying blocking replication

```bash
# Append a message — this should take at least ~5s because secondary2
# is configured with REPLICATION_DELAY_MS=5000, and the master only
# responds after every secondary has acked.
time curl -s -X POST localhost:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello"}'

# All three logs should now agree:
curl -s localhost:8080/messages   # master
curl -s localhost:8081/messages   # secondary1
curl -s localhost:8082/messages   # secondary2
```

## Tests

```bash
mvn -q test
```

- `common`: `Message` JSON round-trip.
- `secondary`: replication appends messages in order, and blocks for the
  configured delay before acking.
- `master`: `ReplicationCoordinator` blocks until every (fake) secondary has
  acked, including the slowest one.
