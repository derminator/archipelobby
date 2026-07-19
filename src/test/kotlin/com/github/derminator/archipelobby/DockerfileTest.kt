package com.github.derminator.archipelobby

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DockerfileTest {

    @Test
    fun `Docker image installs Archipelago pinned requirements before dropping privileges`() {
        val dockerfile = Path.of("Dockerfile").toFile().readText()
        val dependencyInstall = "RUN /app/.venv/bin/python Archipelago/ModuleUpdate.py --yes --force"

        assertContains(dockerfile, dependencyInstall)
        assertTrue(
            dockerfile.indexOf(dependencyInstall) < dockerfile.indexOf("USER appuser"),
            "Archipelago dependencies must be installed before the runtime user is selected",
        )
    }
}
