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
    }
}
