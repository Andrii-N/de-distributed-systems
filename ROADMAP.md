# Roadmap: build the replicated log yourself

Goal: rebuild the Iteration 1 replicated log (1 master + 2 secondaries, blocking REST replication, logging, Docker) by hand, using the generated project only as a reference you consult, never copy from.

## How to use the generated project

1. Commit the generated code on its own branch so it stays untouched: `git switch -c reference-generated`, commit, then `git switch -c hw-iteration-one-own` from your starting point.
2. On your working branch, keep only `README.md`, `.gitignore` and this file. Start every milestone with an empty folder.
3. Work in this loop for each milestone: read the goal and hints, try it alone for a fixed time (30-45 min), only then read the matching reference file, close it, and write your own version from memory.
4. Commit at the end of every milestone (`git commit -m "M3: secondary GET /messages"`). Small commits let you roll back when something breaks.
5. Never paste from the reference. If you catch yourself wanting to, re-read the docs for the class involved instead.

Rough total: 10-16 hours spread over several sittings.

## Concepts to understand before coding (about 1 hour)

Be able to explain each of these in one or two sentences before you start:

- **Replicated log:** an append-only list of messages that is copied to several nodes so each holds the same data in the same order.
- **Master/secondary (leader/follower):** only the master accepts writes; secondaries only receive copies and serve reads.
- **Blocking (synchronous) replication:** the master answers the client only after every secondary has acknowledged. Consequence: latency equals the slowest secondary, and one dead secondary blocks writes.
- **Acknowledgement (ack):** a reply that means "I stored it". Decide what your ack looks like (status code, body).
- **Perfect link assumption:** messages are never lost, duplicated or reordered on the wire. That is why this iteration has no retries or timeouts logic to design.
- **Total order:** every node must list messages in the same order. Ask yourself what could break this with two clients posting at once.

Read: the course notes on replication, and the JDK docs for `com.sun.net.httpserver.HttpServer` and `java.net.http.HttpClient`.

## Milestone 0: environment check (15 min)

- `java -version` reports 25, `mvn -version` works, `docker version` works.
- Done when: all three commands succeed in a fresh terminal.

## Milestone 1: Maven multi-module skeleton (1 hour)

Goal: three modules (`common`, `master`, `secondary`) under one parent that build together.

- Learn: parent POM with `<packaging>pom</packaging>`, `<modules>`, `dependencyManagement` vs `dependencies`, `maven.compiler.release`.
- Hint: modules depend on `common` through a normal `<dependency>`; the parent's version property keeps them aligned.
- Done when: `mvn package` at the root builds all three modules with no source code yet, and the reactor output lists them in the order common, master, secondary.

## Milestone 2: the `Message` model and JSON (1-2 hours)

Goal: a value class shared by both services that converts to and from JSON.

- Decide the fields (an id, the text, a timestamp) and why the master, not the secondaries, should assign the id.
- Decide how to do JSON. You can add a small library such as `org.json` or Gson, or write it by hand. If you choose by hand, notice how quickly escaping quotes becomes painful; that is the reason libraries exist.
- Add JUnit 5 to the POM and write a test first: object to JSON to object equals the original.
- Learn: `equals`/`hashCode`, why the class should be immutable.
- Done when: the round-trip test passes.

## Milestone 3: secondary v1, read-only (1-2 hours)

Goal: a process that starts an HTTP server and answers `GET /messages` with `[]`.

- Learn: `HttpServer.create`, `createContext`, `HttpExchange`, `sendResponseHeaders`, and the rule that you must send headers before writing the body and close the exchange.
- Gotchas: the response length argument (a positive number means "exactly this many bytes"), the `Content-Type` header, and that the default executor runs everything on one thread.
- Read the port from an environment variable with a default. Note `System.getenv` (real environment variables) is not the same as `-D` system properties (`System.getProperty`).
- Done when: `curl -i localhost:8081/messages` shows status 200, a JSON content type and `[]`.

## Milestone 4: secondary receives replicated messages (2 hours)

Goal: `POST /replicate` appends a message; `GET /messages` returns it.

- Design the in-memory store. Think about which threads touch it (each HTTP request may run on a different thread) and pick a thread-safe approach. Write down why a plain `ArrayList` is not safe here.
- Reject wrong methods with 405 and bad bodies with 400.
- Done when: two `curl -X POST` calls to `/replicate` followed by `GET /messages` return both messages in order.

## Milestone 5: artificial delay on the secondary (30-45 min)

Goal: configurable sleep before the ack so you can prove replication blocks.

- A `REPLICATION_DELAY_MS` environment variable, default 0. Decide where in the handler the sleep goes (before or after appending) and what that means for a client that gives up waiting. Write down your choice.
- Learn: `Thread.sleep` and how to handle `InterruptedException` properly (restore the interrupt flag).
- Done when: with delay 3000, `time curl -X POST ...` takes about 3 seconds.

## Milestone 6: master v1, local only (1-2 hours)

Goal: master accepts and lists messages without any replication.

- `POST /messages` with body `{"message": "..."}` assigns the id and timestamp, appends locally and returns 201 with the stored message. `GET /messages` returns the list.
- The id generator must be thread-safe. Learn `AtomicLong`.
- Validate input: return 400 for a missing `message` field.
- Done when: posting three messages gives ids 1, 2, 3 and GET shows them in order.

## Milestone 7: replicate to one secondary, sequentially (2 hours)

Goal: master calls a secondary and waits for the ack before answering the client.

- Learn: `HttpClient.newHttpClient()`, `HttpRequest.newBuilder`, `HttpResponse.BodyHandlers`, `client.send` (blocking).
- Read secondary addresses from a `SECONDARY_URLS` environment variable (comma-separated). Think about why a config value beats hard-coding.
- Treat any non-200 as a failure and answer the client with 502. Note this is enough for a perfect link.
- Done when: run one secondary locally on port 8081 and master with `SECONDARY_URLS=http://localhost:8081`; a POST to the master shows the message on both.

## Milestone 8: two secondaries, sequential then parallel (2-3 hours)

Goal: understand why parallel fan-out matters, by measuring it.

1. First loop over the secondaries one by one. Give secondary A 2 s of delay and secondary B 3 s. Measure the master POST time: it should be about 5 s (the sum).
2. Then send to all secondaries at once and wait for all of them. Learn `CompletableFuture.runAsync`, `CompletableFuture.allOf`, `join`, and what executor to run the tasks on (look up virtual threads in Java 21+, or a fixed thread pool).
3. Measure again: about 3 s (the slowest one).
4. Decide what happens when one future fails. Read what `join()` throws and how to unwrap it.
- Done when: you can explain in your own words why the sum became a maximum, and your numbers prove it.

## Milestone 9: ordering under concurrency (1-2 hours)

Goal: find and fix the race yourself.

- Experiment: remove any locking and fire many concurrent POSTs (for example a small loop with `curl ... &`, or a Java test using several threads). Compare the order of ids on master and on the secondaries. Can you produce a mismatch?
- Fix it by making the "assign id, append locally, replicate, wait for acks" sequence one critical section. Learn `synchronized` and `ReentrantLock`.
- Think about the cost: what does this do to throughput? Why is that acceptable for this homework?
- Done when: 50 concurrent posts leave identical lists on all three nodes.

## Milestone 10: logging (1 hour)

Goal: logs that tell the story of one request across all three processes.

- Learn: SLF4J API plus a backend (Logback) and a `logback.xml` with a console appender and a timestamp/thread pattern.
- Log at least: master receives POST, master appends, master dispatches to each secondary, each ack received, total time, secondary receives, secondary simulates delay, secondary acks, and errors with the stack trace.
- Do not log with string concatenation; use `{}` placeholders.
- Done when: reading only the logs from the three processes you can reconstruct the timeline of one POST.

## Milestone 11: tests (2 hours)

Goal: automated proof of the requirements.

- Secondary test: start the server on port 0 (the OS picks a free port), POST, GET, check order, check the delay is respected.
- Master test: start two fake secondaries (small `HttpServer`s inside the test), one slow; assert the master call takes at least the slow one's delay and that both received the message.
- Learn: `@AfterEach` for cleanup, why tests should never use fixed ports.
- Done when: `mvn test` is green and breaks if you remove the wait-for-all logic (try it: delete the join, watch the test fail).

## Milestone 12: Docker (2-3 hours)

Goal: each service in its own container, all started with one command.

- Write one multi-stage Dockerfile per service: a build stage with the JDK and Maven, a runtime stage that only copies the jar. Learn why multi-stage keeps images small.
- The jar must be runnable on its own: learn what a "fat/uber jar" is and the Maven Shade plugin with a `Main-Class` manifest entry. Test with `java -jar` before Docker.
- Learn build context: with a multi-module project, the context must be the repo root so `common` is visible.
- Write `docker-compose.yml` with `master`, `secondary1`, `secondary2`. Inside the Compose network services reach each other by service name and container port, never `localhost`. Map different host ports for curl.
- Give one secondary a large `REPLICATION_DELAY_MS`.
- Gotchas on Windows: Git Bash rewrites paths starting with `/` (set `MSYS_NO_PATHCONV=1` or use `//`); `docker compose up` before the images finish building; port already in use.
- Done when: `docker compose up --build` then the curl timing test from Milestone 5 shows the delay on the master POST and all three GETs match.

## Milestone 13: final verification and demo (1 hour)

Run this checklist and keep the output for your submission:

- [ ] `POST /messages` on master blocks about as long as the slowest secondary.
- [ ] `GET /messages` on master, secondary1 and secondary2 return identical lists.
- [ ] Logs on all three services show the full flow.
- [ ] `docker compose down` then `up` starts clean (in-memory data is gone, and you can explain why).
- [ ] Bad input gives 400, wrong method gives 405.
- [ ] Stop one secondary and post: you should see the master fail. Explain what a real system would do differently (retries, timeouts, quorum). This is a likely follow-up question.

## Milestone 14 (only if required): dev container

The assignment text asks for the project to start through a VS Code dev container. Learn `devcontainer.json`, the `dockerComposeFile`, `service` and `runServices` fields, and add a small extra Compose service the editor attaches to. Test with "Dev Containers: Reopen in Container".

## Self-check questions

Answer these without looking anything up before you call it finished:

1. Why does the master's POST latency equal the slowest secondary rather than the sum?
2. What happens to a POST if a secondary is down? What would you change in a later iteration?
3. Why is the id assigned by the master?
4. What could reorder messages on the secondaries, and which line of your code prevents it?
5. Why do the containers use `http://secondary1:8080` and not `localhost`?
6. What guarantees does a perfect link give you, and which of your code paths would need to change if the link could drop messages?
7. Why can `GET /messages` on a secondary lag behind the master, and can it ever be ahead?

## When you get stuck

1. Reproduce with the smallest possible curl command.
2. Read the logs of every process, not just the one that errored.
3. Check the JDK docs for the exact class before searching elsewhere.
4. Only after 30 minutes stuck, read the matching reference file for that one problem, close it, and continue in your own words.
