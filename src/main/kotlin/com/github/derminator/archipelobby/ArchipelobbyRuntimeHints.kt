package com.github.derminator.archipelobby

import com.github.derminator.archipelobby.data.Entry
import com.github.derminator.archipelobby.data.EntryInfo
import com.github.derminator.archipelobby.data.Room
import com.github.derminator.archipelobby.data.RoomWithEntries
import com.github.derminator.archipelobby.discord.GuildInfo
import com.github.derminator.archipelobby.discord.UserInfo
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.registerType

class ArchipelobbyRuntimeHints : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // Flyway SQL migration scripts
        hints.resources().registerPattern("db/migration/*.sql")

        // Thymeleaf templates
        hints.resources().registerPattern("templates/*.html")
        hints.resources().registerPattern("templates/**/*.html")

        // Static resources
        hints.resources().registerPattern("static/*")

        // Application data classes used with R2DBC mapping and Jackson
        hints.reflection().registerType<Room>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )
        hints.reflection().registerType<Entry>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )
        hints.reflection().registerType<RoomWithEntries>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )
        hints.reflection().registerType<EntryInfo>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )
        hints.reflection().registerType<GuildInfo>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )
        hints.reflection().registerType<UserInfo>(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
        )

        // Discord4J JSON model classes require reflection for Jackson deserialization.
        // These are registered via the reflect-config.json in META-INF/native-image.
        registerDiscord4jJsonModels(hints, classLoader)
    }

    private fun registerDiscord4jJsonModels(hints: RuntimeHints, classLoader: ClassLoader?) {
        // Discord4J uses Immutables-generated classes for all Discord API payloads.
        // The builder pattern means each ImmutableXxxData and ImmutableXxxData.Builder
        // needs reflective access. The most commonly used types are listed here;
        // the full set is covered by reflect-config.json under META-INF/native-image.
        val discord4jClasses = listOf(
            "discord4j.discordjson.json.ImmutableUserData",
            "discord4j.discordjson.json.ImmutableGuildData",
            "discord4j.discordjson.json.ImmutableMessageData",
            "discord4j.discordjson.json.ImmutableChannelData",
            "discord4j.discordjson.json.ImmutableMemberData",
            "discord4j.discordjson.json.ImmutableRoleData",
            "discord4j.discordjson.json.ImmutableEmojiData",
            "discord4j.discordjson.json.ImmutableGatewayData",
            "discord4j.discordjson.json.ImmutableActivityData",
            "discord4j.discordjson.json.ImmutablePresenceData",
            "discord4j.discordjson.json.ImmutableReadyData",
            "discord4j.discordjson.json.ImmutableUnavailableGuildData",
        )
        for (className in discord4jClasses) {
            try {
                val clazz = classLoader?.loadClass(className) ?: Class.forName(className)
                hints.reflection().registerType(
                    clazz, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS
                )
            } catch (_: ClassNotFoundException) {
                // Class not on classpath — skip
            }
        }
    }
}
