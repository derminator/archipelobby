CREATE TABLE rooms
(
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    guild_id BIGINT       NOT NULL,
    name     VARCHAR(255) NOT NULL
);

CREATE TABLE room_members
(
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE CASCADE
);
