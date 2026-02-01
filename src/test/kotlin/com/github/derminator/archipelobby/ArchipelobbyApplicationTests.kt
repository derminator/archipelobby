package com.github.derminator.archipelobby

import discord4j.core.GatewayDiscordClient
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    properties = [
        "DISCORD_BOT_TOKEN=dummy",
        "DISCORD_CLIENT_ID=dummy",
        "DISCORD_CLIENT_SECRET=dummy"
    ]
)
class ArchipelobbyApplicationTests {

    @MockitoBean
    lateinit var gatewayDiscordClient: GatewayDiscordClient

    @Test
    fun contextLoads() {
    }

}
