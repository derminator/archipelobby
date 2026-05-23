# Code review & durability analysis — multiserver branches

Scope: everything I introduced across PRs #59, #60, #61, including the second-round revisions. JDK 25 from Temurin was downloaded locally to `/tmp/jdk25` so the suite could be exercised against a JDK that matches the toolchain.

## Test results

All three branches, on the post-fix commits I just pushed:

| Branch | Commit | Tests |
|---|---|---|
| `feature/issue-12-run-archipelago-server` (PR #59) | `033e67b` | 63/63 pass |
| `feature/issue-20-multiworld-tracker` (PR #60) | `66826d5` | passes (rebase clean, same suite) |
| `feature/issue-51-check-off-locations` (PR #61) | `2552434` | passes |

`./gradlew test --no-daemon` against an isolated `GRADLE_USER_HOME=/tmp/gradle-home`. Two tests needed asserter fixes when `localhost:38281` stopped being rendered — moved those into #59 itself (`a04bdc5`) so the patch chain is consistent.

There's an existing compiler warning on `DiscordPrincipalConverter.kt:73` (elvis on a non-nullable). It pre-dates this branch; left alone.

## Real bugs I found and fixed in this review pass (`033e67b`)

These were in code I wrote. Pushed as a follow-up commit on #59.

### 1. Log-thread cleanup race — the bug the reviewer originally caught, re-introduced

When I went from `processes.remove(roomId, managed)` to plain `processes.remove(roomId)` (because the temp ManagedServer was gone), I re-opened the exact race derminator flagged on the original PR.

Sequence:

1. `startServer` puts `ManagedServer-A` in the map and releases the per-room lock.
2. Process A dies.
3. Log thread A is still draining buffered stdout.
4. A new `startServer` runs, sees `processes[roomId]?.process.isAlive == false`, starts process B, puts `ManagedServer-B`.
5. Log thread A finally finishes draining, `waitFor()` returns instantly, calls `processes.remove(roomId)` — wipes B.

Fix: keep the unconditional remove out, capture the actual `ManagedServer` reference in the log thread via `lateinit var`, and use the two-arg `processes.remove(roomId, managed)` so only the entry the thread owns gets cleared.

### 2. `allocatePort` cross-room race — the `allocationMutex` didn't actually cover the window

The mutex serialises the *scan*, but releases before the caller adds the new `ManagedServer` to `processes`. Two concurrent `startServer` calls for different rooms could both scan an empty `processes` and pick the same port. The Mutex made me think I'd fixed this; I hadn't.

Fix: a `pendingPorts: Set<Int>` populated under the same mutex during allocation, included in the "used" set on subsequent calls, and removed in `finally` after the `ManagedServer` is committed (or after a failure).

### 3. `MultiServerProxyHandler.forward` leaked Netty DataBuffers

Spring's `WebSocketMessage` ships pooled `DataBuffer` payloads. Reading bytes out doesn't release the reference count. On a chatty connection (Archipelago is) this slowly bleeds the pooled allocator's freelist; pool exhaustion eventually OOMs or stalls the netty loop. Fix is one line: `DataBufferUtils.release(payload)` after the copy.

### 4. `multiserver_wrapper.py` HTTP calls had no timeout

All three `urlopen` sites (game-data fetch at startup, GET save, PUT save) used the global default — which is "no timeout". A GC pause or hung Spring would freeze the save loop forever, taking the auto-save mechanism with it. Added a 30 s timeout constant and applied it to all three.

## Issues I'm flagging but didn't fix

### Medium

**Save data write races against admin Room edits.** `Room` is `@Version` for optimistic locking. `SaveDataService.put` does `findById → save(copy(savedGameData = bytes))`. The wrapper saves every ~60 s; concurrently, an admin clicking "Delete Generated Game" runs `roomRepository.save(...)`. The losing write throws `OptimisticLockingFailureException`, the wrapper catches it and logs. The save is lost. Since the wrapper saves again at the next interval and admin actions are rare, the practical impact is minimal — but I want this in writing. A targeted single-column update (`UPDATE rooms SET saved_game_data = ? WHERE id = ?`) would dodge the version check entirely and is the right fix if it ever becomes a real problem.

**Token-in-URL leaks via access logs.** `/internal/multiserver/{token}/...` puts the per-process secret in the path. Standard practice has been to keep secrets out of URLs because access logs, proxy logs, and metrics dashboards capture them. The `internalBaseUrl` defaults to `http://localhost:8080` so the realistic exposure is local-only, but if someone configures a non-loopback internal URL or runs an HTTP proxy in front, this becomes a real disclosure path. Moving the token to an `Authorization: Bearer` header would close it.

**`Room.savedGameData: ByteArray` and data-class identity.** Kotlin generates `equals`/`hashCode` over the constructor args; for `ByteArray` those use reference identity, not content. Nothing in the current code paths actually compares two `Room` instances for equality, so today this is a latent footgun, not a bug. Worth a comment on the class or — better — moving the blob into its own `APSAVES` table with a `roomId` foreign key, so `Room` stays a clean value type.

### Low

**`isRunning()` returns `running = true` immediately after `start()` is invoked, regardless of whether the launched auto-start has actually started any servers.** Matches Spring's loose contract but doesn't reflect "all servers up" — so a client connecting in the gap will get `CloseStatus.SERVICE_RESTARTED` from the proxy.

**WebSocket proxy doesn't propagate close codes.** When the upstream closes, the downstream just gets the proxy's own close. For Archipelago this is fine because state is at the application layer, but a more diligent proxy would forward the codes.

**`ReactorNettyWebSocketClient` is a field, not lifecycle-managed.** Netty cleans up on JVM shutdown so it's not a real leak, but it's not great citizenship.

**Wrapper imports `MultiServer` twice** (inside `install_save_hooks` and again in `main`). Python caches imports — cosmetic, not a bug.

## What I think holds up

The interesting durability questions:

- **What if Spring restarts while a game is mid-flight?** SmartLifecycle calls `stop(Runnable)`, which delegates to `stopServer` per room (with per-room locks), the wrapper process catches SIGTERM, runs MultiServer's `atexit` hook, calls the patched `_save`, which PUTs to Spring. As long as the PUT lands before Spring's shutdown gets too far, the save survives. If the PUT fails (Spring already gone), the wrapper logs and we lose the most recent ~60 s of progress — but the previous save is intact in the DB. On Spring restart, `start()` enumerates `findByGeneratedGameFilePathIsNotNull()`, calls `startServer(roomId)` for each, and the wrapper's `init_save` GETs the persisted blob and resumes.
- **What if the wrapper itself dies mid-save?** PUT is atomic from Spring's perspective (R2DBC single save call). Either the new blob lands or it doesn't. The previous good blob is preserved on failure. No half-written state.
- **What if a port collides with something the OS already owns?** `runInBackground` spawns the wrapper, which runs `MultiServer.main(...)` which calls `websockets.serve(host, port)`. If the port is taken, Python raises on the `serve` call, the wrapper exits non-zero, our log thread logs the failure, the entry is removed via the CAS, `pendingPorts` is cleared. Recoverable; next start attempt picks a different port (after we add the dead port to the in-memory used set on the next try, since `processes` is empty for that room). Slightly noisy but durable.
- **What if `archipelobby.multiserver.enabled=false`?** `ConditionalOnProperty` short-circuits the `ProcessMultiServerManager` bean; `NoOpMultiServerManager` takes over via `ConditionalOnMissingBean`. The proxy handler is still registered but `isRunning(roomId)` always returns false, so requests close with `SERVICE_RESTARTED` cleanly. No crash.
- **What if the per-process `InternalToken` is leaked?** An attacker on the host can hit `/internal/multiserver/{token}/save/{roomId}` and corrupt save data for any room. They cannot escalate to RCE through this surface — `restricted_loads` in the wrapper applies a classname allowlist, so even a malicious pickle won't deserialise arbitrary classes. The blast radius is "corrupt one room's saves until next Spring restart, when the token rotates."

## What I'd want to do next if this were my codebase

- Move save blobs into their own table (`APSAVES(room_id PRIMARY KEY, data BLOB)`) — both for the ByteArray-in-data-class concern and so save writes don't fight version-bump churn on `Room`.
- Put the internal token in an `Authorization: Bearer ...` header instead of the path.
- Add an integration test that actually round-trips through the wrapper's HTTP layer (spin a local Python, point it at a test Spring, write save bytes, kill it, restart, verify state). Right now `WebTests` mocks `MultiServerManager` and never exercises the real Python lifecycle.

Pushed: #59 = `033e67b`, #60 = `66826d5`, #61 = `2552434`. Tests pass on all three.
