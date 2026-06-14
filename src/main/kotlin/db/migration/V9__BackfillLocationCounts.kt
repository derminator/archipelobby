package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

class V9__BackfillLocationCounts : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val conn = context.connection

        data class EntryRow(val id: Long, val roomId: Long, val yamlFilePath: String)
        val entries = buildList {
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT id, room_id, yaml_file_path FROM ENTRIES WHERE location_count IS NULL"
                ).use { rs ->
                    while (rs.next()) {
                        add(EntryRow(rs.getLong("id"), rs.getLong("room_id"), rs.getString("yaml_file_path")))
                    }
                }
            }
        }

        for (entry in entries) {
            val apWorldPaths = buildList {
                conn.prepareStatement("SELECT file_path FROM APWORLDS WHERE room_id = ?").use { ps ->
                    ps.setLong(1, entry.roomId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) add(rs.getString("file_path"))
                    }
                }
            }

            val count = tryGetLocationCount(entry.yamlFilePath, apWorldPaths)

            conn.prepareStatement("UPDATE ENTRIES SET location_count = ? WHERE id = ?").use { ps ->
                ps.setInt(1, count)
                ps.setLong(2, entry.id)
                ps.executeUpdate()
            }
        }

        conn.createStatement().use {
            it.execute("UPDATE ENTRIES SET location_count = 0 WHERE location_count IS NULL")
        }
        conn.createStatement().use {
            it.execute("ALTER TABLE ENTRIES ALTER COLUMN location_count SET DEFAULT 0")
        }
        conn.createStatement().use {
            it.execute("ALTER TABLE ENTRIES ALTER COLUMN location_count SET NOT NULL")
        }
    }

    private fun tryGetLocationCount(yamlFilePath: String, apWorldPaths: List<String>): Int {
        return try {
            val archipelagoDir = java.io.File("Archipelago/Generate.py").absoluteFile.parent
            val command = buildList {
                add("python")
                add("python/get_location_count.py")
                add(archipelagoDir)
                add(yamlFilePath)
                addAll(apWorldPaths)
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output.trim().toIntOrNull() ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }
}
