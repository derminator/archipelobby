import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    id("org.springframework.boot") version "4.1.0-M4"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.0.0"
    id("org.graalvm.python") version "25.0.2"
}

group = "com.github.derminator"
version = "0.0.1-SNAPSHOT"
description = "archipelobby"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.discord4j:discord4j-core:3.3.2")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework:spring-jdbc")
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:python:25.0.2")
    implementation("org.graalvm.python:python-embedding:25.0.2")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("io.r2dbc:r2dbc-h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-r2dbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

springBoot {
    mainClass.set("com.github.derminator.archipelobby.ArchipelobbyApplicationKt")
}

graalvmNative {
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("archipelobby")
            buildArgs.addAll(
                "--enable-url-protocols=https",
                "-H:+ReportExceptionStackTraces",
            )
        }
    }
}

// Keep in sync with archipelobby.archipelago.package-blacklist in application.properties.
// Entries are pip distribution names, lowercase.
val graalPyPackageBlacklist = setOf(
    "kivy", "kivymd",           // GUI — not needed for generation
    "pyshortcuts", "pymem",     // OS-integration / runtime client
    "orjson", "cymem", "bsdiff4", // native builds that do not support GraalPy
)

fun requirementDistributionName(requirementLine: String): String =
    requirementLine
        .trim()
        .substringBefore("#egg=")
        .substringBefore("@")
        .substringBefore("[")
        .substringBefore(";")
        .split(Regex("[=<>!~\\s]"))[0]
        .trim()
        .lowercase()

fun parseRequirementsFile(file: File): List<String> {
    if (!file.exists()) return emptyList()
    val joined = mutableListOf<String>()
    for (raw in file.readLines()) {
        val line = raw.substringBefore('#').trimEnd()
        if (line.isBlank()) continue
        if (joined.isNotEmpty() && joined.last().endsWith("\\")) {
            joined[joined.lastIndex] = joined.last().dropLast(1).trimEnd() + " " + line.trim()
        } else {
            joined.add(line.trim())
        }
    }
    return joined.filter { requirementDistributionName(it) !in graalPyPackageBlacklist }
}

graalPy {
    packages = (listOf("pip", "setuptools") +
            parseRequirementsFile(file("Archipelago/requirements.txt"))
            ).toSet()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
