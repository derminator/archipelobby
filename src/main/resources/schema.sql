CREATE TABLE ROOMS
(
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT       NOT NULL,
    name           VARCHAR(255) NOT NULL,
    state          VARCHAR(50)  NOT NULL DEFAULT 'WAITING_FOR_PLAYERS',
    server_address VARCHAR(255),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (guild_id, name)
);

CREATE TABLE ENTRIES
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT       NOT NULL,
    user_id BIGINT       NOT NULL,
    name    VARCHAR(255) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE,
    UNIQUE (room_id, name)
);

CREATE TABLE AP_WORLDS
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id     BIGINT       NOT NULL,
    uploaded_by BIGINT       NOT NULL,
    world_name  VARCHAR(255) NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_size   BIGINT       NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE
);

CREATE TABLE YAML_UPLOADS
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    game_name   VARCHAR(255) NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_size   BIGINT       NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE,
    UNIQUE (room_id, user_id)
);

CREATE TABLE SUPPORTED_GAMES
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id          BIGINT       NOT NULL,
    game_name        VARCHAR(255) NOT NULL,
    is_core_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    setup_guide_url  VARCHAR(500),
    yaml_form_url    VARCHAR(500),
    requires_apworld BOOLEAN      NOT NULL DEFAULT FALSE,
    apworld_id       BIGINT,
    added_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE,
    FOREIGN KEY (apworld_id) REFERENCES AP_WORLDS (id) ON DELETE SET NULL
);
