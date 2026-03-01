CREATE TABLE ENTRIES_NEW
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id        BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    yaml_file_path VARCHAR(500) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES ROOMS (id) ON DELETE CASCADE
);

INSERT INTO ENTRIES_NEW (id, room_id, user_id, yaml_file_path)
SELECT id, room_id, user_id, yaml_file_path
FROM ENTRIES;

DROP TABLE ENTRIES;

ALTER TABLE ENTRIES_NEW RENAME TO ENTRIES;
