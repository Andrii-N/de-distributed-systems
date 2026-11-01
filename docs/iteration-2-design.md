# Iteration 2 design notes

Decisions made before coding (Roadmap Iteration 2, Milestone 1). Later milestones build on these, so change them here first if they change.

## 1. Client request: `POST /messages`

```json
{ "message": "hello", "w": 2 }
```

| Field | Required | Meaning |
|---|---|---|
| `message` | yes | The text to append. Validated as in Iteration 1. |
| `w` | no | Write concern: how many nodes, **master included**, must hold the message before the client gets a response. |

### Default when `w` is missing: `w = 1`

If the body has no `w` field, the master treats the request as `w = 1`. It appends locally, answers the client immediately, and replicates to all secondaries in the background.

Why:
- It matches the spec's harness example, where `masterNode.appendMsg("m1")` is called without a write concern.
- It avoids the Milestone 7 trap. With `reorderMessages` holding m1 until m2 arrives, a default that waits for a secondary ack (`w ≥ 2`) would deadlock a single-threaded test. With `w = 1`, `appendMsg("m1")` returns, `appendMsg("m2")` runs, and the transport releases both.

Consequence to be aware of: an Iteration 1 client that never sends `w` used to get "replicated everywhere" semantics. It now gets the weakest guarantee: the response comes before any secondary has the message. This change is deliberate, and the README should say so.

### Validation of `w`

With `n` configured secondaries (from `SECONDARY_URLS`), the only valid values are **integers `1 ≤ w ≤ n + 1`**. With the current Docker setup (`n = 2`), that is `1`, `2` or `3`.

| Input | Result |
|---|---|
| `w` absent | treated as `1` |
| `1` … `n + 1` | accepted |
| `0`, negative, `> n + 1` | `400 Bad Request` |
| not an integer: `2.5`, `"2"`, `true`, `null`, an object | `400 Bad Request` |

Notes:
- An explicit `"w": null` is **rejected**, not treated as missing. "Absent" and "present but empty" are different inputs, and accepting `null` usually hides a client bug.
- Gotcha: a JSON library will happily convert `2.5` to an `int` by truncating it to `2`. Check that the number is integral before converting.
- Validate in two places: the HTTP layer (to answer 400) and `MasterNode.appendMsg` (so the harness, which bypasses HTTP, can't pass an invalid `w` either).

## 2. Response

| Outcome | Status | Body |
|---|---|---|
| Message stored on `w` nodes | `201 Created` | The stored message, same shape as Iteration 1: `{"id": 1, "text": "hello", "timestamp": 1730...}` |
| Invalid body or invalid `w` | `400 Bad Request` | Plain-text reason |
| `w − 1` secondary acks became impossible | `502 Bad Gateway` | Plain-text reason |

The response **does not echo `w`** and **does not report how many acks were received**. The client knows the `w` it sent, and a `201` already means "at least `w` nodes hold it". Reporting the exact count would also be misleading, because more acks keep arriving after the response. Ack counts and timings are reported **in the master's logs only** (Milestone 10).

## 3. Ordering key: `id` becomes a number

`Message.id` changes from `String` to a numeric sequence number (`long`). There is no separate `seq` field: the id *is* the position in the log.

```json
{ "id": 1, "text": "hello", "timestamp": 1730000000000 }
```

Properties the master guarantees:
- **Starts at 1** and increases by exactly 1 per accepted message.
- **No gaps:** every id that is assigned is also appended. If something can fail between assigning and appending, the id must not be consumed. Otherwise every secondary waits forever for a message that was never sent (see section 4).
- **Assigned only by the master.** Secondaries never invent ids; they use them to order (`next expected = last visible + 1`) and to deduplicate (Milestone 6).
- **Timestamps are informational only.** Never order by `timestamp`: wall clocks can jump backwards, and two messages can share a millisecond.

What this touches (to do in code):
- `Message`: field type, constructor, `equals`/`hashCode`, and `fromJson` (an id that is not an integer is invalid).
- The master no longer converts the id to a string when creating a message.
- Existing tests that build `new Message("1", ...)`.
- **Test first:** update `MessageTest`'s JSON round-trip test to the numeric id before changing `Message`.

## 4. Why `AtomicLong.getAndIncrement()` alone does not keep the master's list in order

Assigning an id and appending the message are **two separate operations**. Each one is thread-safe on its own:

- `AtomicLong.getAndIncrement()` guarantees every caller gets a **unique** number, with no duplicates and none skipped.
- `MessageStore.append` (a `synchronizedList`) guarantees concurrent appends don't corrupt the list.

Neither guarantees anything about **what happens between the two calls**. Two request threads run truly in parallel on different cores, and a thread can also be paused by the scheduler at any instruction:

```
Thread A (POST "x")                    Thread B (POST "y")
----------------------------------     ----------------------------------
id = nextId.getAndIncrement()  -> 5
        ... paused / slow ...          id = nextId.getAndIncrement()  -> 6
                                       messages.append(#6 "y")
messages.append(#5 "x")

Master list:  [..., #4, #6, #5]
```

Every id is unique and none is missing, so the `AtomicLong` did its job. But the list is no longer in id order. This kind of bug is called a **compound action**: two individually atomic steps whose combination is not atomic.

Why this breaks the replicated log:
1. **Master and secondaries disagree.** Secondaries order by id (Milestone 6), so they list `#5, #6` while the master lists `#6, #5`. That violates total order, and the *master* is the node that is wrong.
2. **The master can show a gap.** Between B's append and A's append, `GET /messages` on the master returns `#6` while `#5` doesn't exist yet. Secondaries are designed never to do this.

The fix (Milestone 4): make **"assign id + append to the master log"** a single critical section, so no other thread can append between the two steps. Inside that lock, a plain `long` counter would be enough; the `AtomicLong` stays only for convenience. Replication and waiting for acks stay **outside** the lock. Otherwise you are back to the Iteration 1 problem where a slow `w = 3` write blocks a `w = 1` write.

Iteration 1 never showed this bug, because the whole `appendAndReplicate` method was `synchronized`. The lock was too large, but it happened to cover both steps.

## 5. Node identity: logical names

Every node is addressed by a **logical name**, not by its URL. The transport (`Transport.send(from, to, message)`) and the logs use these names.

| Node | Name comes from | Docker value | Default (local run) |
|---|---|---|---|
| master | `NODE_ID` | `master` | `master` |
| each secondary, its own name | `NODE_ID` | `secondary1`, `secondary2` | `secondary` |
| the master's view of the secondaries | `SECONDARY_URLS` as `name=url` pairs | `secondary1=http://secondary1:8080,secondary2=http://secondary2:8080` | `secondary1=http://localhost:8081` |

Rules:
- A `SECONDARY_URLS` entry without a name (`http://localhost:8082`) is named `secondary<N>` by its 1-based position. That keeps the Iteration 1 format working.
- Names must be unique, and an entry with an empty name or URL is rejected at startup.
- Configuration order is preserved, so dispatch and log order are predictable.
- A secondary's own `NODE_ID` should match the name the master uses for it. Nothing enforces this yet; it only affects how the logs read.

Why names instead of URLs: they read better in logs, they match the Docker service names, and the harness (Milestone 3) has no URLs at all, only node objects registered under names.

## 6. Open question: a failed replication leaves the message on the master

`MasterNode.appendMsg` appends the message to the master's log **before** replicating it. If replication then fails, the client gets `502`, but the message stays in the master's log, and on any secondaries that did ack. A client that retries creates a duplicate with a new id. This has been true since Iteration 1 and is documented by `MasterNodeTest.failedReplicationThrowsButKeepsMessageInMasterLog`.

Decide in Milestone 5, when failures become partial (`w` acks reached or not): is a `502` "the write did not happen" or "the write may have happened"? Real systems usually say the latter and make retries safe with deduplication.
