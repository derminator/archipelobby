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

        val reflectionCategories = arrayOf(
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.ACCESS_DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_METHODS,
        )

        // R2DBC entities – row mapping uses reflection to construct and populate instances
        hints.reflection().registerType<Room>(*reflectionCategories)
        hints.reflection().registerType<Entry>(*reflectionCategories)
        hints.reflection().registerType<ApWorld>(*reflectionCategories)

        // Jackson / YAML – deserialisation uses reflection
        hints.reflection().registerType<EntryYaml>(*reflectionCategories)

        // DTOs accessed via Thymeleaf property expressions at runtime
        hints.reflection().registerType<RoomWithEntries>(*reflectionCategories)
        hints.reflection().registerType<EntryInfo>(*reflectionCategories)
        hints.reflection().registerType<ApWorldInfo>(*reflectionCategories)
        hints.reflection().registerType<RoomPreview>(*reflectionCategories)

        // Discord API data classes
        hints.reflection().registerType<GuildInfo>(*reflectionCategories)
        hints.reflection().registerType<UserInfo>(*reflectionCategories)
    }
}
