Another pass on top of `6fc5eca`. Claude on @flounderK's machine.

**Move the save blob into its own table.** New `APSAVES(room_id PK, data BLOB, ON DELETE CASCADE)` and `ApSaveRepository` with a one-statement `MERGE` upsert. Drops the `ByteArray` field from `Room` (which broke data-class equality semantics) and bypasses the `@Version` check on `Room` — so the periodic save loop in the wrapper no longer races admin-driven Room edits.

**Token moved out of the URL.** `MultiServerInternalController` reads `Authorization: Bearer <token>` instead of expanding `{token}` in the path. Returns 404 on a wrong/missing header rather than 401, matching the "endpoint not acknowledged" stance the rest of the app takes.

**Token also moved out of `ps`.** Even with the new header, the wrapper used to receive the token as `--spring-token` argv, which leaks via `/proc/<pid>/cmdline`. Now it's passed via the `ARCHIPELOBBY_SPRING_TOKEN` env var; `PythonScriptRunner.runInBackground` grew an `extraEnv` map for the plumbing.

**WebFlux codec ceiling raised.** Default `spring.codec.max-in-memory-size` is 256 KB and the save PUT uses `@RequestBody ByteArray`, which would clip multi-MB Archipelago saves. Set to 64 MB.

**Lifecycle-managed proxy client.** `MultiServerProxyHandler` owns a named `ConnectionProvider`, hands it to the underlying `HttpClient`, and disposes it in `@PreDestroy` so the connection pool dies with the bean instead of riding out JVM shutdown.

**Wrapper import dedup.** `import MultiServer` happens once in `main()`; `install_save_hooks` takes the module as a parameter now.

**Self-review caught four real bugs in earlier rounds; fixed in `033e67b`:**
- Log-thread cleanup race I'd accidentally re-introduced when I swapped the temp `ManagedServer` for plain `processes.remove(roomId)`. Restored the CAS variant with a `lateinit var` capture so the log thread only clears the entry it owns.
- `allocatePort` cross-room race — the mutex serialised the *scan* but released before the `ManagedServer` was committed, so two concurrent `startServer`s for different rooms could pick the same port. Reserved via a `pendingPorts` set, released in `finally`.
- `MultiServerProxyHandler.forward` leaked Netty `DataBuffer`s. One-line `DataBufferUtils.release(payload)`.
- `multiserver_wrapper.py` `urlopen` had no timeout — a hung Spring would freeze the save loop. 30 s timeout on all three call sites.

Tests on JDK 25 Temurin: #59 = 63/63, #60 = 65/65, #61 = 70/70. One pre-existing compiler warning in `DiscordPrincipalConverter.kt:73` (not from this branch); no new warnings.

Stack: #59 = `4b28477`, #60 = `1bb8419`, #61 = `db229b1`.
