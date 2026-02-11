CREATE TABLE ROOMS
(
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT       NOT NULL,
    name VARCHAR(255) NOT NULL,
    UNIQUE (guild_id, name)
);

CREATE TABLE ENTRIES
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT       NOT NULL,
    user_id BIGINT       NOT NULL,
    name    VARCHAR(255) NOT NULL,
    yaml_file_path VARCHAR(500) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE,
    UNIQUE (room_id, name)
);
