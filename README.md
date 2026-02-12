# Archipelobby

A Spring Boot web application for managing Archipelago multiworld YAML files with Discord authentication. Users can
create rooms organized by Discord guilds, upload YAML files for Archipelago games, and easily download individual or
bundled entries.

## Features

- **Discord OAuth2 Authentication**: Secure login using Discord accounts
- **Discord Bot Integration**: Retrieve user guild memberships and permissions
- **Room Management**: Create and organize rooms by Discord guild
- **YAML File Uploads**: Upload and manage Archipelago YAML configuration files
- **Entry Management**: Add, rename, download, and delete entries within rooms
- **Bulk Download**: Download all YAML files in a room as a ZIP archive
- **Role-Based Access**: Guild administrators have additional permissions for room and entry management
- **Reactive Architecture**: Built with Spring WebFlux for non-blocking, reactive operations
- **R2DBC Database**: Uses reactive database access with H2

## Technology Stack

- **Language**: Kotlin 2.2.21
- **Framework**: Spring Boot 4.0.2
- **Architecture**: Spring WebFlux (Reactive)
- **Database**: H2 with R2DBC
- **Authentication**: Spring Security with OAuth2 (Discord)
- **Discord Integration**: Discord4J 3.3.0
- **Template Engine**: Thymeleaf
- **Build Tool**: Gradle (Kotlin DSL)
- **Java Version**: 21

## Prerequisites

- Java 21 or higher
- Discord application with OAuth2 credentials
- Discord bot token (for guild membership verification)

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

The application will be available at `http://localhost:8080`

## Usage

1. **Login**: Navigate to the application and login with Discord
2. **Create Room**: Select a Discord guild you administer and create a new room
3. **Upload YAML**: In a room, upload Archipelago YAML files with entry names
4. **Manage Entries**: Rename, download, or delete individual entries
5. **Download All**: Download all YAML files in a room as a ZIP archive
6. **Room Administration**: Guild admins can delete rooms and manage all entries

## Development

### Building the Project

```bash
# Build JAR
./gradlew bootJar

# Run tests
./gradlew test

# Build native image (GraalVM)
./gradlew nativeCompile
```

### Project Structure

```
src/main/kotlin/
├── ArchipelobbyApplication.kt        # Main application entry point
├── SecurityConfiguration.kt          # Security and OAuth2 configuration
├── GlobalExceptionHandler.kt         # Global exception handling
├── controllers/
│   ├── IndexController.kt            # Home page controller
│   └── RoomController.kt             # Room and entry management
├── data/
│   ├── Room.kt                       # Data models and repositories
│   └── RoomService.kt                # Business logic for rooms/entries
└── discord/
    ├── DiscordBotConfiguration.kt    # Discord bot client setup
    └── DiscordOAuth2UserService.kt   # Custom OAuth2 user service
```

## Database

The application uses an embedded H2 database with R2DBC. The database schema is initialized from
`src/main/resources/schema.sql` and includes:

- `ROOMS`: Stores room information (guild ID, name)
- `ENTRIES`: Stores YAML file entries (room ID, user ID, name, file path)

In production mode, the database is persisted to the configured data directory.

## Profiles

- **Default**: Uses in-memory H2 database
- **prod**: Uses file-based H2 database at `${DATA_DIR}/db`

To run in production mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

## API Endpoints

- `GET /` - Home page
- `GET /rooms` - List all rooms
- `POST /rooms` - Create a new room
- `GET /rooms/{roomId}` - View room details and entries
- `POST /rooms/{roomId}/entries` - Upload a new YAML entry
- `POST /rooms/{roomId}/entries/{entryId}/rename` - Rename an entry
- `POST /rooms/{roomId}/entries/{entryId}/delete` - Delete an entry
- `GET /rooms/{roomId}/entries/{entryId}/download` - Download a YAML file
- `GET /rooms/{roomId}/download-all` - Download all entries as ZIP
- `POST /rooms/{roomId}/delete` - Delete a room (admin only)

## License

This project is licensed under the terms specified in the repository.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.
