-- Adds the game_name column that records which Archipelago game an apworld
-- provides. Combined with a per-room unique constraint, the database itself
-- guarantees that only one apworld in any room can claim a given game — even
-- under concurrent uploads.

ALTER TABLE APWORLDS ADD COLUMN game_name VARCHAR(200) NOT NULL DEFAULT '';

-- Backfill existing rows. file_name is already unique per room
-- (uq_apworlds_room_filename), so using it as the placeholder preserves
-- uniqueness for legacy apworlds whose true game name is unknown.
UPDATE APWORLDS SET game_name = file_name WHERE game_name = '';

-- Drop the default; new inserts must supply game_name explicitly.
ALTER TABLE APWORLDS ALTER COLUMN game_name DROP DEFAULT;

ALTER TABLE APWORLDS ADD CONSTRAINT uq_apworlds_room_game UNIQUE (room_id, game_name);
