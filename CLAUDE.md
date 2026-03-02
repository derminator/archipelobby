# Archipelobby — AI Assistant Guide

A Spring Boot/Kotlin web application for managing [Archipelago](https://archipelago.gg) multiworld YAML files with Discord authentication.

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.2.21 |
| Framework | Spring Boot 4.0.2 |
| Web layer | Spring WebFlux (reactive, non-blocking) |
| Database | H2 with R2DBC (reactive) |
| Migrations | Flyway |
| Auth | Spring Security OAuth2 (Discord) |
| Discord SDK | Discord4J 3.3.0 |
| Templates | Thymeleaf |
| Build | Gradle (Kotlin DSL) |
| Java version | 21 |

## Project Structure

```
src/main/kotlin/com/github/derminator/archipelobby/
├── ArchipelobbyApplication.kt         # Main entry point (@SpringBootApplication)
├── SecurityConfiguration.kt           # OAuth2 / form-login security chain
├── WebFluxConfiguration.kt            # Trailing-slash UrlHandlerFilter
├── GlobalExceptionHandler.kt          # @ControllerAdvice → error/404 or error/error templates
│
├── controllers/
│   ├── IndexController.kt             # GET /
│   └── RoomController.kt             # /rooms/** endpoints
│
├── data/
│   ├── Room.kt                        # Room, Entry entities + ReactiveCrudRepository interfaces
│   └── RoomService.kt                # Business logic; orchestrates repos + DiscordService
│
├── discord/
│   ├── DiscordService.kt             # Interface for Discord operations
│   ├── RealDiscordService.kt         # Production impl via Discord4J GatewayDiscordClient
│   ├── DevDiscordService.kt          # Dev impl backed by application properties
│   ├── DiscordBotConfiguration.kt    # Wires GatewayDiscordClient (prod "discord" profile)
│   ├── DevDiscordConfiguration.kt    # Wires DevDiscordService + form-login users (dev)
│   └── DiscordOAuth2UserService.kt   # OAuth2 user loading from Discord
│
├── security/
│   ├── DiscordPrincipal.kt           # Custom Principal (userId + username); extension fun
│   └── DiscordPrincipalConverter.kt  # WebFilter converting Spring auth → DiscordPrincipal
│
└── storage/
    ├── UploadsService.kt             # Interface: saveFile, getFile, deleteFile, fileExists
    ├── FileSystemUploadsService.kt   # Prod impl: saves to ${app.data-dir}/uploads/
    └── InMemoryUploadsService.kt     # Dev impl: stores files in-memory

src/main/resources/
├── application.properties            # Shared config (OAuth2 provider, app name, default profile)
├── application-dev.properties        # Dev: in-memory H2 + dev Discord stub users/guilds
├── application-prod.properties       # Prod: file-based H2 at ${app.data-dir}
├── db/migration/V1__InitialDb.sql   # Flyway schema: ROOMS and ENTRIES tables
└── templates/
    ├── layout.html                   # Shared Thymeleaf layout
    ├── index.html                    # Home page
    ├── rooms.html                    # Room list
    ├── room.html                     # Room detail + entry management
    └── error/
        ├── 404.html
        └── error.html

src/test/kotlin/com/github/derminator/archipelobby/
├── ArchipelobbyApplicationTests.kt   # Context load smoke test
├── WebTests.kt                       # Full web-layer tests (WebTestClient + Mockito)
└── TrailingSlashHandlingTest.kt      # Verifies trailing-slash redirect behaviour
```

## Spring Profiles

The active profile set drives both auth mode and storage:

| Profile | Auth | DiscordService | UploadsService | Database |
|---|---|---|---|---|
| `dev` (default) | Form login | `DevDiscordService` | `InMemoryUploadsService` | In-memory H2 |
| `discord` | OAuth2 (Discord) | `RealDiscordService` | `InMemoryUploadsService` | In-memory H2 |
| `prod` (activates `discord`) | OAuth2 (Discord) | `RealDiscordService` | `FileSystemUploadsService` | File H2 at `${app.data-dir}` |

The property `spring.profiles.group.prod=discord` means activating `prod` also activates `discord`.

## Development Workflow

### Prerequisites

- Java 21+
- For dev mode: no Discord credentials needed
- For prod/discord mode: Discord application OAuth2 credentials + bot token

### Running Locally (Dev Mode — no Discord credentials)

```bash
./gradlew bootRun
```

Dev mode uses form login. Users are defined in `application-dev.properties`:

```properties
archipelobby.discord.dev.users=admin,member,outsider
archipelobby.discord.dev.guilds=12345:Main Guild,67890:Other Guild
archipelobby.discord.dev.user-guilds=admin:12345,admin:67890,member:12345
archipelobby.discord.dev.admin-guilds=admin:12345
```

All dev users have password `password`. User IDs are their zero-based index in the `users` list.

### Running with Discord OAuth2

```bash
export DISCORD_CLIENT_ID=your_client_id
export DISCORD_CLIENT_SECRET=your_client_secret
export DISCORD_BOT_TOKEN=your_bot_token
./gradlew bootRun --args='--spring.profiles.active=discord'
```

### Running in Production Mode

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

Requires all three Discord env vars. Data is persisted to `./data/` by default.

### Running Tests

```bash
./gradlew test
```

### Building

```bash
./gradlew bootJar          # Standard JAR
./gradlew nativeCompile    # GraalVM native image
```

## Key Architectural Conventions

### Reactive + Coroutines

Controllers and services use Kotlin coroutines (`suspend fun`) wrapped in `mono { }` blocks rather than raw Reactor types. Follow this pattern consistently:

```kotlin
// Good — controller method
@GetMapping
fun myEndpoint(principal: Principal): Mono<String> = mono {
    val userId = principal.asDiscordPrincipal.userId
    // ... use suspend functions freely
    "viewName"
}

// Good — service method
suspend fun doSomething(id: Long): SomeType {
    return repository.findById(id).awaitSingle()
}
```

Avoid returning raw `Flux`/`Mono` from service methods; use `Flow` or suspend functions instead.

### DiscordPrincipal

All controllers receive a `Principal` and convert it via the `asDiscordPrincipal` extension property:

```kotlin
val userId = principal.asDiscordPrincipal.userId   // Long
val username = principal.asDiscordPrincipal.username
```

Never reach into `OAuth2User` attributes directly in controllers.

### Profile-Based Bean Selection

Implementations are swapped by Spring profiles using `@Profile`:

- `@Profile("discord")` — real Discord integration
- `@Profile("!discord")` — dev stubs
- `@Profile("prod")` — file-based storage

When adding a new injectable service that differs between dev and prod, follow this same pattern: define an interface, provide two `@Component`/`@Bean` implementations guarded by `@Profile`.

### Error Handling

Throw `ResponseStatusException` with an appropriate `HttpStatus` from service methods. `GlobalExceptionHandler` catches all exceptions and renders the correct error template.

### Security

- CSRF is enabled (using `XorServerCsrfTokenRequestAttributeHandler` with multipart support).
- All pages except `/`, `/error`, and `*.css` require authentication.
- When writing form-submit tests, always apply `.mutateWith(csrf())`.

## Database

Flyway manages schema migrations. Migration files live in `src/main/resources/db/migration/` and follow the naming convention `V{n}__{Description}.sql`.

**Schema overview:**

```sql
ROOMS   (id BIGINT PK, guild_id BIGINT, name VARCHAR, UNIQUE(guild_id, name))
ENTRIES (id BIGINT PK, room_id BIGINT FK→ROOMS, user_id BIGINT, name VARCHAR,
         yaml_file_path VARCHAR, UNIQUE(room_id, name))
```

## API Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/` | Home page |
| GET | `/rooms` | List rooms for current user |
| POST | `/rooms` | Create room (admin only) |
| GET | `/rooms/{roomId}` | Room detail + entries |
| POST | `/rooms/{roomId}/entries` | Upload YAML entry (multipart) |
| POST | `/rooms/{roomId}/entries/{entryId}/rename` | Rename entry (owner only) |
| POST | `/rooms/{roomId}/entries/{entryId}/delete` | Delete entry (owner or admin) |
| GET | `/rooms/{roomId}/entries/{entryId}/download` | Download single YAML |
| GET | `/rooms/{roomId}/download-all` | Download all YAMLs as ZIP |
| POST | `/rooms/{roomId}/delete` | Delete room (guild admin only) |

## Testing Conventions

Tests use `@SpringBootTest` with `WebTestClient` bound to the application context. R2DBC auto-configuration is excluded (`@EnableAutoConfiguration(exclude = [R2dbcAutoConfiguration::class])`); repositories and `DiscordService` are replaced with `@MockitoBean`.

Authentication is simulated via `mockAuthentication(UsernamePasswordAuthenticationToken(discordPrincipal, ...))`.

Key patterns:
- Always use `.mutateWith(csrf())` for POST requests in tests.
- Use `AutoConfigureWebTestClient` or build `WebTestClient.bindToApplicationContext(context).apply(springSecurity())` manually.
- Test access control by setting `discordService.isAdminOfGuild(...)` to `true`/`false`.

## CI/CD

`.github/workflows/docker-publish.yml` builds multi-arch Docker images (amd64 + arm64) and pushes to GitHub Container Registry (`ghcr.io`) on pushes to `main` or version tags (`v*`).

The Dockerfile:
1. Builds the JAR using `gradle:9.2-jdk21`
2. Runs with `eclipse-temurin:21-jre-jammy` as a non-root user (`appuser`, uid/gid 1000)
3. Activates `prod` profile via `-Dspring.profiles.active=prod`
4. Exposes port 8080
