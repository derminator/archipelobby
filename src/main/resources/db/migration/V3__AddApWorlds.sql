CREATE TABLE APWORLDS (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id   BIGINT       NOT NULL,
    user_id   BIGINT       NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE,
    CONSTRAINT uq_apworlds_room_filename UNIQUE (room_id, file_name)
);
