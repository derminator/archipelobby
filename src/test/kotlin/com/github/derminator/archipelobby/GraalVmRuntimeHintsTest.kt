package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.ApWorld
import com.github.derminator.archipelobby.data.ApWorldInfo
import com.github.derminator.archipelobby.data.Entry
import com.github.derminator.archipelobby.data.EntryInfo
import com.github.derminator.archipelobby.data.EntryYaml
import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomPreview
import com.github.derminator.archipelobby.data.RoomWithEntries
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

/**
 * Verifies that [ArchipelobbyRuntimeHints] registers all types and resources required at
 * native-image runtime. A missing entry here means the class or resource would be absent
 * from the native binary and cause a failure that is otherwise only discovered after a full
 * native compile + run cycle.
 */
class GraalVmRuntimeHintsTest {

    private lateinit var hints: RuntimeHints

    @BeforeEach
    fun setup() {
        hints = RuntimeHints()
        ArchipelobbyRuntimeHints().registerHints(hints, null)
    }

    // ---- helpers ----

    private fun assertReflectionRegistered(type: Class<*>) {
        assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(type)
                .withMemberCategory(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                )
        ).`as`("${type.simpleName} must be registered for reflection with constructors, fields, and methods")
            .accepts(hints)
    }

    private fun assertResourceAccessible(path: String) {
        assertThat(RuntimeHintsPredicates.resource().forResource(path))
            .`as`("resource '$path' must match a registered pattern")
            .accepts(hints)
    }

    // ---- R2DBC entities (row mapping uses reflection) ----

    @Test
    fun `Room is registered for reflection`() = assertReflectionRegistered(Room::class.java)

    @Test
    fun `Entry is registered for reflection`() = assertReflectionRegistered(Entry::class.java)

    @Test
    fun `ApWorld is registered for reflection`() = assertReflectionRegistered(ApWorld::class.java)

    // ---- Jackson YAML deserialisation ----

    @Test
    fun `EntryYaml is registered for reflection`() = assertReflectionRegistered(EntryYaml::class.java)

    // ---- DTOs accessed via Thymeleaf property expressions ----

    @Test
    fun `RoomWithEntries is registered for reflection`() = assertReflectionRegistered(RoomWithEntries::class.java)

    @Test
    fun `EntryInfo is registered for reflection`() = assertReflectionRegistered(EntryInfo::class.java)

    @Test
    fun `ApWorldInfo is registered for reflection`() = assertReflectionRegistered(ApWorldInfo::class.java)

    @Test
    fun `RoomPreview is registered for reflection`() = assertReflectionRegistered(RoomPreview::class.java)

    // ---- Discord API data classes ----

    @Test
    fun `GuildInfo is registered for reflection`() = assertReflectionRegistered(GuildInfo::class.java)

    @Test
    fun `UserInfo is registered for reflection`() = assertReflectionRegistered(UserInfo::class.java)

    // ---- Resources ----

    @Test
    fun `Flyway migration scripts are accessible`() {
        listOf(
            "db/migration/V1__InitialDb.sql",
            "db/migration/V2__AddGameToEntries.sql",
            "db/migration/V3__AddApWorlds.sql",
        ).forEach(::assertResourceAccessible)
    }

    @Test
    fun `Top-level Thymeleaf templates are accessible`() {
        listOf(
            "templates/index.html",
            "templates/rooms.html",
            "templates/room.html",
            "templates/room-preview.html",
            "templates/layout.html",
        ).forEach(::assertResourceAccessible)
    }

    @Test
    fun `Thymeleaf templates in subdirectories are accessible`() {
        listOf(
            "templates/error/404.html",
            "templates/error/error.html",
        ).forEach(::assertResourceAccessible)
    }

    @Test
    fun `Static resources are accessible`() {
        listOf(
            "static/style.css",
            "static/confirm.js",
            "static/favicon.svg",
            "static/robots.txt",
        ).forEach(::assertResourceAccessible)
    }
}
