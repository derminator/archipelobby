package com.github.derminator.archipelobby.data

object Puns {
    private val puns = listOf(
        "Archipela-go! Let's get this show on the road.",
        "Ready, set, go-nerate your multiworld!",
        "Pokémon, go to the polls... but first, finish this room.",
        "Go-al achieved: you found this room. Now fill it.",
        "Time to go-lden ticket your way through this randomizer!",
        "Go big or go home. You've already gone to Archipelago.",
        "As they say: ready, set, go-ing to lose all your sanity.",
        "Why did the randomizer go to therapy? Too many go-als.",
        "Life is go-od when you're playing Archipelago.",
        "Go ahead, submit your YAML. I won't judge.",
        "This room is go-ing places. Probably to sphere 1.",
        "Go-lly, that's a lot of games.",
        "Let's-a-go! (Wrong franchise, but the energy is right.)",
        "Where do you want to go today? Archipelago!",
        "No need to go it alone — it's a multiworld!",
    )

    fun forRoom(roomId: Long): String = puns[(roomId % puns.size).toInt()]
}
