# Roadmap: Iteration 2 — write concern, total order, test harness

Goal: turn the Iteration 1 blocking replicated log into a **tunable semi-synchronous** one. The client picks a write concern `w`, the master waits for `w - 1` secondary acks, and every node still lists messages in the **same total order**, even when they arrive late or reordered. You prove it with an in-process **test harness** (`MockedTransport`) and with the Docker acceptance scenario.

This file continues [ROADMAP.md](ROADMAP.md). Keep the same way of working: try each milestone alone first, consult the reference only after a fixed time, never paste from it, and commit at the end of each milestone (`git commit -m "I2-M4: write concern in coordinator"`).

Rough total: 16-24 hours over several sittings.

---

## Where Iteration 1 leaves you

Know what you are changing before you change it:

| Piece | Iteration 1 behaviour | Why it is a problem now |
|---|---|---|
| `ReplicatedLog.appendAndReplicate` | `synchronized`: assign id, append, replicate to all, wait, return, all as one critical section | A `w=3` POST waiting ~10 s on a slow secondary holds the lock, so a `w=1` POST behind it also waits. Acceptance step 5 ("Msg4 must not be blocked by Msg3") fails. |
| `ReplicationCoordinator.replicateToAll` | `allOf(...).join()`: waits for every secondary | Needs to wait for exactly `w - 1` acks and let the rest finish in the background. |
| `SecondaryApplication.handleReplicate` | Appends in arrival order, then sleeps | Once POSTs run concurrently, arrival order is no longer id order. The sleep also comes *after* the append, so a delayed secondary already shows the message, and you cannot observe the inconsistency the spec asks for. |
| `Message.id` | `String` | Ordering and gap detection need a number you can compare and count. |
| `SecondaryClient` + HTTP | Master logic is wired straight to HTTP | The spec's `MockedTransport` / `MasterNode` / `SecondaryNode` harness needs a seam between node logic and the network. |

Self-check question 4 from Iteration 1 and the deferred Milestone 9 become part of this iteration. To meet the spec you have to remove the big lock, and then you have to handle the reordering that follows.

---

## Concepts to understand before coding (about 1.5 hours)

Be able to explain each in one or two sentences:

- **Write concern `w`:** how many nodes, master included, must hold a write before the client gets "OK". `w=1` means master only, `w=n+1` means everyone (that was Iteration 1).
- **Semi-synchronous replication:** the client waits for some acks and the remaining replication carries on asynchronously. Replication to *all* secondaries always happens, and `w` only changes **when the client is answered**.
- **Inconsistency window / eventual consistency:** after a `w=1` write, a secondary can temporarily return an older list. If no new writes arrive, all lists converge.
- **Total order:** every node exposes the same sequence. A node may lag behind (be a prefix of the master), but it must never show `Msg4` before `Msg3` or skip `Msg3`.
- **Sequence number:** a monotonically increasing number assigned by the single master. It is what lets a secondary detect "I'm missing #3".
- **Gap / hold-back queue:** a secondary keeps out-of-order messages in a buffer and exposes only the **contiguous prefix** 1..k.
- **Idempotency / deduplication:** receiving the same message twice must not create two entries. You don't strictly need this under a perfect link, but the reorder harness and the next iteration's retries depend on it.
- **Ack semantics:** does an ack mean "I received it and buffered it" or "it is visible in my list"? (You decide this in Milestone 6.)
- **Test double / seam:** an interface between logic and I/O so a test can swap the real network for a controllable fake.

Reading: course notes on consistency models and write concern. MongoDB's "Write Concern" docs are a good real-world parallel. Also read the JDK docs for `CountDownLatch`, `CompletableFuture` (`orTimeout`, `whenComplete`, `delayedExecutor`), `ConcurrentSkipListMap`/`TreeMap`, and `HttpClient.sendAsync`.

---

## Milestone 0: baseline and observe the problem (45 min)

Goal: see Iteration 1 fail the new requirement before you fix anything.

1. Start from the Iteration 1 code on a fresh branch. Run `mvn -q test` and `docker compose up --build`, and make sure both are green.
2. Reproduce acceptance step 5 against the *old* code: fire a POST in the background (it takes ~5 s because of `secondary2`), and immediately fire a second POST in the foreground. Time the second one.
   - Bash: put `&` after the first `curl`. PowerShell: `Start-Job` or a second terminal.
3. Write down the measured time and *which line of code* caused it.

- Done when: you can show that the second POST waited for the first, and you can point at `synchronized` as the cause.

## Milestone 1: decide the API and the ordering key (1 hour, design only)

Goal: settle the contracts before touching code. Write the answers in a short `docs/iteration-2-design.md` (or the README).

> **Decided** (see [docs/iteration-2-design.md](docs/iteration-2-design.md)): missing `w` defaults to `1`; the response doesn't echo `w` or report ack counts; `id` becomes the numeric sequence. What's left for this milestone is the test-first `Message` change.

- **Request body:** `{"message": "...", "w": 2}`. Decide:
  - What happens when `w` is missing? Either reject with 400, or default it. If you default, decide between `w = n+1` (keeps Iteration 1 behaviour) and `w = 1`. Write down why.
  - The valid range: `1 ≤ w ≤ n + 1`, where `n` is the number of configured secondaries. Anything else (0, negative, too large, not an integer, `"2"` as a string?) gets a 400.
- **Response:** still 201 with the stored message. Decide whether to echo `w` back and whether to report how many acks you actually got.
- **Ordering key:** add a numeric sequence to `Message` (a `long seq`, or change `id` to a number). Decide which and update the JSON round-trip test **first** (test-first, as in Iteration 1 M2).
- **Who assigns it:** the master, once, inside a tiny critical section. Explain to yourself why `AtomicLong.getAndIncrement()` alone is *not* enough to guarantee the master's own list is in id order. (Hint: two threads, increment, then append. Can thread B append #6 before thread A appends #5?)

- Done when: the design note answers every bullet and the updated `Message` round-trip test passes.

## Milestone 2: introduce a transport seam (pure refactor, 2-3 hours)

Goal: separate node logic from HTTP so the harness in the spec can exist. **No behaviour change, and all existing tests stay green.**

The spec's harness talks about `MasterNode`, `SecondaryNode` and a `Transport`. Shape your code to match:

- **`Transport` interface (in `common`):** "send this message from node A to node B and give me a future that completes with the ack". Returning a `CompletableFuture` rather than blocking makes write concern much easier later. Think about what identifies a node (a name or id string is enough).
- **`SecondaryNode` (in `secondary`):** plain Java, no HTTP. It has `receive(message)` (what `/replicate` does today) and `listMsgs()`. `SecondaryApplication` becomes a thin HTTP adapter that parses the request, calls the node, and writes the response.
- **`MasterNode` (in `master`):** plain Java. It has `appendMsg(text, w)` and `listMsgs()`. It uses a `Transport` to reach the secondaries instead of a list of `SecondaryClient`s. `MasterApplication` becomes the HTTP adapter.
- **`HttpTransport` (in `master`):** the production implementation, built from what `SecondaryClient` does today. Learn `HttpClient.sendAsync` as an alternative to `send` on a virtual-thread executor, and pick one.

Gotchas:
- Do it in small steps: extract `SecondaryNode`, run tests, commit; then the transport, run tests, commit.
- Keep the existing names where they still fit (`ReplicatedLog` can become or live inside `MasterNode`). Don't rename for the sake of it.

- Done when: `mvn test` is green, Docker still behaves exactly like Iteration 1, and neither `MasterNode` nor `SecondaryNode` imports anything from `com.sun.net.httpserver` or `java.net.http`.

## Milestone 3: a `harness` module with a basic `MockedTransport` (2 hours)

Goal: an in-process "network" you control. Reordering comes later, in Milestone 7.

- **Where it lives:** the harness needs `MasterNode` *and* `SecondaryNode`, but `master` and `secondary` don't depend on each other. Add a fourth Maven module (for example `harness`) that depends on both, holds `MockedTransport`, and holds the cross-node tests. Learn what changes in the parent POM's `<modules>` and how the reactor order follows from the dependencies.
- **`MockedTransport` v1:**
  - Nodes register with it, so it can route by node id.
  - Delivery happens **on another thread**, never on the caller's thread. Otherwise a "slow" delivery would block `appendMsg` and you'd be testing the wrong thing.
  - Per-link (or per-node) artificial delay. Learn `CompletableFuture.delayedExecutor` or a `ScheduledExecutorService`.
  - Shuts down cleanly in `@AfterEach`.
- **A waiting helper for tests:** replicas converge *eventually*, so a test must poll `listMsgs()` until it matches or a timeout expires. Write one small `awaitUntil(condition, timeout)` helper. Never use a fixed `Thread.sleep` as your assertion strategy, because it is either flaky or slow.

- Done when: a harness test with `M + S1 + S2` and no delay shows a message on all three nodes, and the same test with a 2 s delay on S2 shows S2 *temporarily* lagging before converging.

## Milestone 4: shrink the master's critical section (1-2 hours)

Goal: concurrent POSTs stop blocking each other. This is the deferred Iteration 1 Milestone 9 experiment, done for real.

1. Identify the *minimum* work that must be atomic: assign the sequence **and** append to the master's log. Replication and waiting for acks must happen **outside** the lock.
2. Choose the tool: a smaller `synchronized` block, or a `ReentrantLock`. Write one sentence on why you chose it.
3. Reproduce the race on purpose. With the lock narrowed, fire many concurrent `appendMsg` calls through `MockedTransport` with random per-message delays. Compare the secondary lists to the master list. You should see secondaries listing messages **out of order**. Keep this test: it is red now and turns green in Milestone 6.

- Done when: the Milestone 0 experiment, re-run as a harness test, shows the second write no longer waiting for the first, *and* you have a failing test showing the secondary order diverging. Commit both and note the failure in the commit message.

## Milestone 5: write concern in the coordinator (2-3 hours)

Goal: `appendMsg(text, w)` returns after the master plus `w - 1` secondaries hold the message, and replication to the rest continues in the background.

- **Dispatch to all secondaries every time**, regardless of `w`. `w` only decides how many acks you *wait for*.
- **Wait for `w - 1` acks.** Options to research and pick one:
  - `CountDownLatch(w - 1)`, where each successful ack counts down and the caller `await`s.
  - A `CompletableFuture` you complete yourself when an `AtomicInteger` ack counter reaches `w - 1`.
  - Note why `CompletableFuture.anyOf` / `allOf` alone don't express "k of n".
- **`w = 1`:** don't wait at all, but still dispatch. Make sure nothing in your code path accidentally `join`s the background futures.
- **Background futures must not be cancelled** when you return to the client, and their failures must still be logged (`whenComplete`). Otherwise errors after the response vanish silently.
- **Failures:** if so many secondaries fail that `w - 1` acks become impossible, answer 502 instead of waiting forever. Decide whether you detect this early (count failures) or with a timeout. The perfect-link assumption still holds, so keep it simple, but don't hang.
- **Validation:** `MasterNode` should reject an out-of-range `w` itself, not only the HTTP layer.

Tests (harness, with delays on S2):
- `w=1` returns well before S2's delay.
- `w=2` returns after S1 (fast) but before S2 (slow).
- `w=3` returns only after S2.
- After a `w=1` write, S2 eventually has the message (background replication really happened).

- Done when: all four tests pass, and they break if you change the wait count to "all" or to "none".

## Milestone 6: total order on the secondary (3-4 hours)

Goal: whatever order messages arrive in, a secondary lists only the contiguous prefix `1..k`, in order, without duplicates. This turns Milestone 4's red test green.

- **Data structure:** you need two things:
  - the **visible log**: the contiguous prefix, which `listMsgs()` returns;
  - the **pending buffer**: messages whose predecessor hasn't arrived yet, keyed by sequence (a sorted map).
  - Also track the **next expected sequence**.
- **On receive:**
  1. If the message's sequence is already visible or already pending, it is a duplicate. Ignore it, but still ack.
  2. Otherwise put it in pending.
  3. While pending contains the next expected sequence, move it to the visible log and advance.
- **Thread safety:** receive and list can run on many threads at once. Steps 1-3 must be one atomic operation. Write down why `Collections.synchronizedList` around the visible log (Iteration 1's `MessageStore`) is not enough any more. (Hint: step 3 reads and writes two structures.)
- **Ack semantics:** you have to make a decision here. Write it down:
  - *Ack on receipt:* ack as soon as the message is buffered. This is simpler and honest about "I stored it". But a `w=3` OK doesn't guarantee S2's `GET` shows the message yet, because it may still be behind a gap.
  - *Ack on visible:* the handler waits until the message is part of the contiguous prefix. Then a `w=3` OK means "S2 lists everything up to this message". This is stronger but needs waiting/notification (`wait`/`notifyAll`, a `Condition`, or a per-sequence future), and a gap delays acks of later messages (head-of-line blocking).
  - Pick one. Make sure your acceptance check in Milestone 8 matches what you picked.
- **Master side:** check that the master's own log also stays in sequence order, which follows from Milestone 4's critical section.

Unit tests (on `SecondaryNode` directly, no transport):
- Receive 1, 2, 3 → lists 1, 2, 3.
- Receive 2, 3 → lists nothing. Then receive 1 → lists 1, 2, 3.
- Receive 1, 3 → lists only 1 (3 is hidden behind the gap).
- Receive 1, 1, 2 → lists 1, 2 (dedup).
- Concurrency: 1..N delivered from many threads in shuffled order → lists 1..N.

- Done when: these pass and the Milestone 4 divergence test is green.

## Milestone 7: `MockedTransport.reorderMessages` (2 hours)

Goal: the spec's harness method. It deterministically reorders the next N messages on one link.

`transport.reorderMessages(masterNode, secondaryNode1, messagesCount = 2)` means: "on the link master → S1, hold the next 2 messages, then deliver them in a different order."

Design decisions to write down:
- **Which order?** Reversed is deterministic and easy to reason about. Shuffled with a fixed seed is more general. Start with reversed.
- **One-shot or permanent?** Once N messages have been reordered, does the link go back to normal? One-shot matches the spec's example best.
- **Fewer than N messages arrive:** the held messages would be stuck forever. Add a flush on timeout, or document that the test must send at least N.
- **Interaction with write concern (an important trap):** if `appendMsg("m1", w=2)` waits for S1's ack, but the transport is *holding* m1 until m2 arrives, and m2 is only sent after `appendMsg("m1")` returns, you get a **deadlock** in a single-threaded test. Work out on paper why. Then decide: does the spec example use `w=1` (the default?), or must the test issue the appends from separate threads? Make your default-`w` decision from Milestone 1 consistent with this.
- **Ack-on-visible + reorder:** if you chose ack-on-visible, trace m2 delivered first → its ack waits → m1 delivered → both ack. Confirm it can't deadlock.

Test (the spec's example):
- Master + S1 + S2, reorder 2 messages on master → S1, `appendMsg("m1")`, `appendMsg("m2")`.
- Assert the transport really delivered m2 before m1. Record the delivery order in the transport and assert on it, otherwise the test proves nothing.
- `awaitUntil` S1 lists `m1, m2`.

- Done when: the test passes, and it **fails** if you temporarily disable the ordering logic from Milestone 6. Try it.

## Milestone 8: the acceptance test, in the harness (1-2 hours)

Goal: encode the spec's acceptance scenario as an automated test.

Setup: M + S1 (no delay) + S2 (delay 5-10 s; use shorter delays in the test, for example 1-2 s, so the suite stays fast).

1. `appendMsg(Msg1, w=1)` → returns quickly.
2. `appendMsg(Msg2, w=2)` → returns after S1.
3. `appendMsg(Msg3, w=3)` **on another thread** → still waiting because of S2.
4. While 3 is in flight: `appendMsg(Msg4, w=1)` → returns quickly. Assert its latency is far below S2's delay. This proves it isn't blocked by step 3.
5. Check S2 → a prefix of `[Msg1, Msg2, Msg3]`. The parentheses in the spec mean "may or may not be there yet". Assert *prefix-of*, not equality, and assert `Msg4` is not visible without `Msg3`.
6. Check M and S1 → exactly `[Msg1, Msg2, Msg3, Msg4]`.
7. Wait for step 3 to complete, then `awaitUntil` S2 equals the master. This is the eventual consistency.

- Done when: the test is green and stable over 10 repeated runs (look up `@RepeatedTest`).

## Milestone 9: HTTP layer and secondary delay (1-2 hours)

Goal: the real services speak the new API.

- **Master `POST /messages`:** parse and validate `w` according to your Milestone 1 decisions, call `MasterNode.appendMsg(text, w)`, and map errors (400 for bad `w`, 502 when acks are impossible).
- **Master knows `n`:** the number of configured secondaries bounds `w`. Read it from `SECONDARY_URLS` as before.
- **Secondary delay placement:** move the sleep **before** the message is stored, so a delayed secondary really lacks the message during the window. Note how this reverses the Iteration 1 choice ("GET mid-delay includes message") and why.
- **Random delay range:** "delay 5-10 sec" suggests a random delay per message. Add something like `REPLICATION_DELAY_MIN_MS` / `REPLICATION_DELAY_MAX_MS` (or keep one value plus a jitter). Random per-message delays also make messages arrive **out of order in the real system**, so Docker exercises your Milestone 6 logic too.
- **Concurrency on the secondary:** `/replicate` must keep running requests in parallel (the cached thread pool you already have). Ask yourself what would happen with a single-threaded executor while S2 sleeps on Msg3.
- Update the HTTP-level tests (`SecondaryApplicationTest`, master tests) to the new body and delay placement.

- Done when: `curl` with `{"message":"x","w":4}` against 2 secondaries returns 400, `w=1` returns immediately, `w=3` takes about S2's delay.

## Milestone 10: logging (45 min)

Goal: logs that explain the semi-synchronous timeline.

Log at least:
- Master: POST received with `w`, sequence assigned, dispatch per secondary, each ack (with running count `k/w-1`), "responding to client after k acks in X ms", and **background** acks/failures that arrive *after* the response.
- Secondary: received seq X, delay applied (actual random value), buffered because of a gap (expected Y), applied seq X..Z, duplicate ignored, ack sent.

- Done when: from the three containers' logs alone you can see Msg4 being answered while Msg3 is still waiting, and S2 buffering an out-of-order message.

## Milestone 11: Docker and the manual acceptance run (1-2 hours)

- Update `docker-compose.yml`: `secondary2` gets the 5-10 s random delay. Leave `secondary1` without one.
- Write the acceptance scenario as a script you can rerun: one for Git Bash, one for PowerShell (you run Windows), or one of them plus clear instructions.
  - Step 3 (Msg3, `w=3`) must run in the background (`&` in Bash, `Start-Job` / `Start-Process` in PowerShell). Remember to wait for it at the end.
  - Print timestamps per step so the output itself proves Msg4 wasn't blocked.
  - After step 4, GET all three nodes immediately, then again after ~10 s to show convergence.
- Remember the Git Bash path-rewriting gotcha from Iteration 1 if your script passes paths.
- Update `README.md`: Iteration 2 title, the request body with `w`, the new env variables, and how to run the acceptance script and the harness tests.

- Done when: a fresh `docker compose up --build` + the script reproduces every line of the spec's acceptance test, with S2 visibly lagging and then converging.

## Milestone 12: final verification (1 hour)

Run this checklist and keep the output for your submission:

- [ ] `w=1`, `w=2`, `w=3` latencies match "master only", "fastest secondary", "slowest secondary".
- [ ] Msg4 (`w=1`) completes while Msg3 (`w=3`) is still blocked.
- [ ] S2 temporarily shows a strict prefix of the master's list, never a list with gaps or out of order, then converges.
- [ ] The `reorderMessages` harness test passes, and fails without the ordering logic.
- [ ] Invalid `w` (0, n+2, non-integer, missing if you chose to reject) gives 400.
- [ ] Concurrent POST stress (for example 50 parallel `w=1` posts): all three nodes eventually list identical sequences.
- [ ] Stop S2 and post with `w=2` (works) and `w=3` (fails with 502, doesn't hang). Explain what Iteration 3 (retries, heartbeats, quorum) would change.
- [ ] `mvn test` green and repeatable. Logs tell the full story.

---

## Self-check questions

Answer these without looking anything up:

1. With `w=1`, does the message still reach every secondary? Which code path guarantees it, and what happens to its failures?
2. Why did the Iteration 1 `synchronized` method have to shrink, and what exactly must stay inside the critical section?
3. Why isn't `AtomicLong` alone enough to keep the master's own list ordered?
4. What does a secondary do with message #5 when it has only seen #1-#3? What does `GET` return at that moment, and why is that still "total order"?
5. What did you decide an ack means (received vs visible)? Give one advantage and one cost of the other choice.
6. Why can `reorderMessages` + `w=2` deadlock a single-threaded test, and how did you avoid it?
7. Why is deduplication needed if the link is "perfect"? Which upcoming feature makes it mandatory?
8. Can a secondary ever be *ahead* of the master? Can two secondaries list different messages at the same instant? Is either a violation of total order?
9. Why must `MockedTransport` deliver on a different thread than the sender?
10. With `n` secondaries, what are the valid `w` values, and what is the latency of each in terms of the secondaries' delays?

## When you get stuck

1. Reproduce in the harness first. It is faster and deterministic compared to Docker.
2. If a test hangs, take a thread dump (`jstack <pid>` or `jcmd <pid> Thread.print`) and look for who is waiting on what. Most bugs in this iteration are a held lock or an un-completed future.
3. Read the logs of every node, not just the master.
4. Only after 30 minutes stuck, read the matching reference file for that one problem, close it, and continue in your own words.
