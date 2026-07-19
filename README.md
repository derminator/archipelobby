# Archipelobby

A Spring Boot web application for managing Archipelago multiworld YAML files with Discord authentication. Users can
create rooms organized by Discord guilds, upload YAML files for Archipelago games, generate multiworlds directly from
uploaded YAMLs, and easily download individual or bundled entries.

## Features

- **Discord OAuth2 Authentication**: Secure login using Discord accounts
- **Discord Bot Integration**: Retrieve user guild memberships and permissions
- **Room Management**: Create and organize rooms by Discord guild
- **YAML File Uploads**: Upload and manage Archipelago YAML configuration files
- **Entry Management**: Add, rename, download, and delete entries within rooms
- **APWorld Support**: Upload custom `.apworld` game files so their YAMLs can be accepted and generated
- **Multiworld Generation**: Generate an Archipelago multiworld from a room's YAMLs and APWorlds, producing patch
  files, a generated game bundle, and an optional walkthrough
- **Bulk Download**: Download all YAML files in a room as a ZIP archive
- **Role-Based Access**: Guild administrators have additional permissions for room and entry management
- **Reactive Architecture**: Built with Spring WebFlux for non-blocking, reactive operations
- **R2DBC Database**: Uses reactive database access with H2, versioned via Flyway migrations

## Technology Stack

- **Language**: Kotlin 2.4.0
- **Framework**: Spring Boot 4.1.0-RC1
- **Architecture**: Spring WebFlux (Reactive)
- **Database**: H2 with R2DBC, schema managed by Flyway
- **Authentication**: Spring Security with OAuth2 (Discord)
- **Discord Integration**: Discord4J 3.3.2
- **Template Engine**: Thymeleaf
- **Build Tool**: Gradle (Kotlin DSL)
- **Java Version**: 25
- **Multiworld Generation**: Archipelago (bundled as a git submodule), invoked via Python subprocess

## Architecture

Most external dependencies (Discord, file storage, Archipelago generation) are hidden behind an interface with two
implementations, selected by Spring profile:

| Interface                    | Production impl                | Dev/test impl                          |
|-------------------------------|---------------------------------|------------------------------------------|
| `DiscordService`               | `RealDiscordService` (`discord` profile) | `DevDiscordService` (`!discord` profile, configured via `archipelobby.discord.dev.*` properties) |
| `UploadsService`                | `FileSystemUploadsService`     | `InMemoryUploadsService`                  |
| `ArchipelagoGeneratorService`   | `RealArchipelagoGeneratorService` (shells out to the `Archipelago` submodule via Python) | test doubles in `src/test` |

The `prod` profile activates the `discord` profile group (see `application.properties`), which wires up the real
Discord OAuth2/bot integration; the default `dev` profile uses the in-memory/simulated implementations so the app
runs locally with no external credentials. **When changing one of these interfaces, update every implementation**,
not just the one you're testing against.

## Prerequisites

- Java 25 or higher
- Python 3 (3.11–3.13) with `pip` available on `PATH` — used to run Archipelago's generation scripts
- Git (with submodule support, for the bundled `Archipelago` checkout)
- Discord application with OAuth2 credentials
- Discord bot token (for guild membership verification)

## Getting the Code

This project embeds [Archipelago](https://github.com/ArchipelagoMW/Archipelago) as a git submodule, which is required
to generate multiworlds. Clone with submodules included:

```bash
git clone --recurse-submodules <repo-url>
```

If you already cloned without `--recurse-submodules`, fetch it afterwards:

```bash
git submodule update --init --recursive
```

## Configuration

The application requires the following environment variables:

### Required Environment Variables

```bash
# Discord OAuth2 credentials (for user login)
DISCORD_CLIENT_ID=your_discord_client_id
DISCORD_CLIENT_SECRET=your_discord_client_secret

# Discord bot token (for guild membership/admin checks)
DISCORD_BOT_TOKEN=your_discord_bot_token
# Optional: Data directory for uploads and database
DATA_DIR=/path/to/data  # Defaults to ./data
```

### Setting up Discord Application

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a new application
3. Navigate to OAuth2 settings and add redirect URI: `http://localhost:8080/login/oauth2/code/discord`
4. Copy the Client ID and Client Secret
5. Navigate to Bot settings and create a bot
6. Copy the Bot Token
7. Enable necessary bot permissions and invite the bot to your Discord server

## Running the Application

### Using Gradle

```bash
# Set environment variables
export DISCORD_CLIENT_ID=your_client_id
export DISCORD_CLIENT_SECRET=your_client_secret
export DISCORD_BOT_TOKEN=your_bot_token

# Run the application
./gradlew bootRun
```

By default the app runs with the `dev` profile active, which uses an in-memory H2 database and a simulated Discord
service (see `application-dev.properties`) so it can be run locally without real Discord credentials.

### Using Docker

```bash
# Build the Docker image
docker build -t archipelobby .

# Run the container
docker run -p 8080:8080 \
  -e DISCORD_CLIENT_ID=your_client_id \
  -e DISCORD_CLIENT_SECRET=your_client_secret \
  -e DISCORD_BOT_TOKEN=your_bot_token \
  -v /path/to/data:/data \
  archipelobby
```

The application generates its internal MultiServer token on startup. Set
`ARCHIPELOBBY_MULTISERVER_TOKEN` explicitly only when running multiple
application replicas or when MultiServer wrapper processes can survive an
application restart.

The application will be available at `http://localhost:8080`

The Docker build installs the bundled Archipelago and world dependencies into
its Python virtual environment. Room pages can therefore list games without an
interactive dependency-installation prompt or network access at runtime.

## Usage

1. **Login**: Navigate to the application and login with Discord
2. **Create Room**: Select a Discord guild you administer and create a new room
3. **Upload YAML**: In a room, upload Archipelago YAML files with entry names
4. **Upload APWorlds**: Upload any custom `.apworld` files needed by the room's games
5. **Manage Entries**: Rename, download, or delete individual entries
6. **Generate**: Generate a multiworld from the room's YAMLs, producing patch files, a generated game bundle, and a
   walkthrough
7. **Download All**: Download all YAML files in a room as a ZIP archive
8. **Room Administration**: Guild admins can delete rooms and manage all entries and APWorlds

## Development

### Building the Project

```bash
# Build JAR
./gradlew bootJar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.github.derminator.archipelobby.ZipUtilsTest"
```

Running the app or its tests locally requires `python3` (3.11–3.13) with `pip` available on `PATH`; the app spawns
CPython as a subprocess to run Archipelago's generation scripts. Tests live under `src/test/kotlin`, mirroring the
package layout of `src/main/kotlin`.

### Notes for Contributors (Human or AI)

- The `dev` profile (default) must keep working without real Discord credentials or network access — don't add code
  paths that assume `RealDiscordService`/`RealArchipelagoGeneratorService` are always present.
- Database schema changes go in a new Flyway migration under `src/main/resources/db/migration`, named
  `V{next-number}__Description.sql` (or `.kt` for a Java/Kotlin-based migration, see `V9__BackfillLocationCounts.kt`).
  Never edit an already-applied migration; add a new one instead.
- Reactive/coroutine code mixes Kotlin coroutines with Project Reactor via `mono { }` builders and
  `kotlinx-coroutines-reactor`; keep controller/service methods suspending or `Mono`/`Flux`-returning rather than
  blocking.
- Server-rendered pages are Thymeleaf templates in `src/main/resources/templates`, with static assets (CSS/JS) in
  `src/main/resources/static`.
- Run `./gradlew test` before committing; CI (`.github/workflows/test.yml`) runs the same command on JDK 25 /
  Python 3.13.

### Project Structure

```
src/main/kotlin/
├── ArchipelobbyApplication.kt        # Main application entry point
├── SecurityConfiguration.kt          # Security and OAuth2 configuration
├── WebFluxConfiguration.kt           # WebFlux configuration
├── WebSessionConfiguration.kt        # Reactive session configuration
├── GlobalExceptionHandler.kt         # Global exception handling
├── PatchFiles.kt                     # Patch file helpers
├── ZipUtils.kt                       # ZIP bundling helpers
├── controllers/
│   ├── IndexController.kt            # Home page controller
│   └── RoomController.kt             # Room, entry, APWorld, and generation management
├── data/
│   ├── Room.kt                       # Data models and repositories
│   ├── RoomService.kt                # Business logic for rooms/entries
│   ├── EntryYaml.kt                  # YAML entry parsing
│   └── Puns.kt                       # Room naming helpers
├── discord/
│   ├── DiscordBotConfiguration.kt    # Discord bot client setup
│   ├── DiscordOAuth2UserService.kt   # Custom OAuth2 user service
│   ├── DiscordService.kt             # Discord service abstraction
│   ├── RealDiscordService.kt         # Production Discord service
│   ├── DevDiscordConfiguration.kt    # Simulated Discord config for local dev
│   └── DevDiscordService.kt          # Simulated Discord service for local dev
├── generator/
│   ├── ArchipelagoGeneratorService.kt      # Multiworld generation abstraction
│   ├── RealArchipelagoGeneratorService.kt  # Generation via Archipelago's Generate.py
│   ├── GameCatalogService.kt               # Lists generatable games (core + APWorlds)
│   └── PythonScriptRunner.kt                # Runs Archipelago's Python scripts as subprocesses
├── security/
│   ├── DiscordPrincipal.kt           # Discord-backed authentication principal
│   └── DiscordPrincipalConverter.kt  # Converts OAuth2 tokens to DiscordPrincipal
└── storage/
    ├── UploadsService.kt             # File storage abstraction
    ├── FileSystemUploadsService.kt   # Production file-based storage
    └── InMemoryUploadsService.kt     # In-memory storage for dev/tests
```

## Database

The application uses an embedded H2 database with R2DBC. The schema is versioned with Flyway migrations under
`src/main/resources/db/migration` and includes:

- `ROOMS`: Stores room information (guild ID, name, generated game/walkthrough file paths)
- `ENTRIES`: Stores YAML file entries (room ID, user ID, name, game, file path, location count)
- `ENTRY_PATCH_FILES`: Stores generated patch files associated with an entry
- `APWORLDS`: Stores uploaded `.apworld` files (room ID, user ID, file name/path, game name)

In production mode, the database is persisted to the configured data directory.

## Profiles

- **dev** (default): Uses an in-memory H2 database and a simulated Discord service, for local development without
  real Discord credentials
- **prod**: Uses a file-based H2 database at `${DATA_DIR}/db` and the real Discord OAuth2/bot integration

To run in production mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

## API Endpoints

- `GET /` - Home page
- `GET /rooms` - List all rooms
- `POST /rooms` - Create a new room
- `GET /rooms/{roomId}` - View room details, entries, and APWorlds
- `POST /rooms/{roomId}/entries` - Upload a new YAML entry
- `POST /rooms/{roomId}/entries/{entryId}/rename` - Rename an entry
- `POST /rooms/{roomId}/entries/{entryId}/delete` - Delete an entry
- `GET /rooms/{roomId}/entries/{entryId}/download` - Download a YAML file
- `GET /rooms/{roomId}/patches/{patchId}/download` - Download a generated patch file
- `GET /rooms/{roomId}/apworlds/{apworldId}/download` - Download an uploaded APWorld file
- `POST /rooms/{roomId}/apworlds/{apworldId}/delete` - Delete an uploaded APWorld file
- `GET /rooms/{roomId}/download` - Download all entries as ZIP
- `POST /rooms/{roomId}/generate` - Generate a multiworld from the room's YAMLs and APWorlds
- `POST /rooms/{roomId}/upload-game` - Upload an APWorld game file
- `POST /rooms/{roomId}/generated-game/delete` - Delete the room's generated game bundle
- `GET /rooms/{roomId}/generated-game/download` - Download the generated game bundle
- `GET /rooms/{roomId}/walkthrough/download` - Download the generated walkthrough
- `POST /rooms/{roomId}/delete` - Delete a room (admin only)

## License

This project is licensed under the terms specified in the repository.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.
