# Replicated Log

Learning project for a distributed systems course: one master and two secondaries replicating an append-only message log over HTTP.

- **Iteration 1** (done): blocking replication. The master answers a POST only after every secondary has acked.
- **Iteration 2** (in progress): tunable write concern `w` and total ordering. See [ROADMAP-ITERATION-2.md](ROADMAP-ITERATION-2.md) and the design decisions in [docs/iteration-2-design.md](docs/iteration-2-design.md). Until Milestone 5 lands, replication still behaves as in Iteration 1.

## Running with Docker Compose

```bash
docker compose up --build
```

This starts three nodes:

| Service | Node id | Host port | Replication delay |
|---|---|---|---|
| `master` | `master` | 8080 | n/a |
| `secondary1` | `secondary1` | 8081 | none |
| `secondary2` | `secondary2` | 8082 | 5 s |

## Trying it out / verifying blocking replication

```bash
# Append a message. This should take at least ~5s because secondary2
# is configured with REPLICATION_DELAY_MS=5000, and the master only
# responds after every secondary has acked.
time curl -s -X POST localhost:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"message":"hello"}'
# -> {"id":1,"text":"hello","timestamp":...}

# All three logs should now agree:
curl -s localhost:8080/messages   # master
curl -s localhost:8081/messages   # secondary1
curl -s localhost:8082/messages   # secondary2
```

## Configuration

Configuration is read from environment variables (`System.getenv()`), not `-D` flags.

| Variable | Used by | Default | Meaning |
|---|---|---|---|
| `PORT` | both | `8080` | HTTP port to listen on. |
| `NODE_ID` | both | `master` / `secondary` | Logical name of this node, used in logs and by the transport. |
| `SECONDARY_URLS` | master | `secondary1=http://localhost:8081` | Comma-separated `name=url` pairs. An entry without a name (`http://localhost:8082`) is named `secondary<N>` by its position. Names must be unique. |
| `REPLICATION_DELAY_MS` | secondary | `5000` | Artificial delay before acking a replicated message. Set it to `0` for a fast secondary. |

A secondary's `NODE_ID` should match the name the master gives it in `SECONDARY_URLS`. This only affects how the logs read.

## Running locally (no Docker)

Requires JDK 25+ and Maven.

Bash:

```bash
mvn -q package

PORT=8081 NODE_ID=secondary1 REPLICATION_DELAY_MS=0 java -jar secondary/target/secondary-all.jar &
PORT=8082 NODE_ID=secondary2 REPLICATION_DELAY_MS=5000 java -jar secondary/target/secondary-all.jar &
PORT=8080 NODE_ID=master \
  SECONDARY_URLS="secondary1=http://localhost:8081,secondary2=http://localhost:8082" \
  java -jar master/target/master-all.jar &
```

PowerShell (one terminal per service; `$env:` values stay set for the rest of that terminal session):

```powershell
$env:PORT="8081"; $env:NODE_ID="secondary1"; $env:REPLICATION_DELAY_MS="0"; java -jar secondary/target/secondary-all.jar
$env:PORT="8082"; $env:NODE_ID="secondary2"; $env:REPLICATION_DELAY_MS="5000"; java -jar secondary/target/secondary-all.jar
$env:PORT="8080"; $env:NODE_ID="master"; $env:SECONDARY_URLS="secondary1=http://localhost:8081,secondary2=http://localhost:8082"; java -jar master/target/master-all.jar
```

In PowerShell 5.1, `curl` is an alias for `Invoke-WebRequest`. Use `curl.exe` to run the curl examples above, or `Invoke-RestMethod`.

## Tests

```bash
mvn -q test
```

- `common`:
  - `Message` JSON round-trip and id validation (the id must be an integer ≥ 1);
  - parsing `PORT` and `SECONDARY_URLS`.
- `secondary`:
  - `SecondaryNode` logic without HTTP: arrival order, copy-on-list, delay, interrupt handling;
  - the HTTP adapter: listing, delay, 405 for wrong methods, 400 for invalid bodies.
- `master`: `ReplicationCoordinator` blocks until every (fake) secondary has acked, including the slowest one, and fails when any secondary fails.
