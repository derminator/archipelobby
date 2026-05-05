#!/usr/bin/env python3
"""
Get the location count for an Archipelago player YAML.

Usage: get_location_count.py <archipelago_dir> <yaml_path> [<apworld_path>...]

Prints the integer location count on stdout.
Exits non-zero if the game is unknown or initialization fails.
APWorld paths are loaded before built-in worlds so custom games are registered.
"""
import importlib
import os
import random as rand_module
import sys


def main():
    if len(sys.argv) < 3:
        print("Usage: get_location_count.py <archipelago_dir> <yaml_path> [<apworld_path>...]",
              file=sys.stderr)
        sys.exit(1)

    archipelago_dir = os.path.abspath(sys.argv[1])
    yaml_path = os.path.abspath(sys.argv[2])
    apworld_paths = [os.path.abspath(p) for p in sys.argv[3:]]

    sys.path.insert(0, archipelago_dir)

    # Load APWorlds before built-in worlds so custom game classes are registered.
    # .apworld files are ZIP packages; adding them to sys.path lets Python import
    # them directly. Importing triggers the AutoWorld metaclass registration.
    for apworld_path in apworld_paths:
        if not os.path.isfile(apworld_path):
            continue
        sys.path.insert(0, apworld_path)
        package_name = os.path.basename(apworld_path).removesuffix(".apworld")
        try:
            importlib.import_module(package_name)
        except Exception as e:
            print(f"Warning: could not load APWorld {apworld_path!r}: {e}", file=sys.stderr)

    # Importing worlds registers all built-in game world classes.
    import worlds  # noqa: E402
    from worlds.AutoWorld import AutoWorldRegister

    import yaml
    with open(yaml_path, "r", encoding="utf-8") as f:
        player_data = yaml.safe_load(f)

    game_name = player_data.get("game")
    if not isinstance(game_name, str):
        print(f"Unsupported game field type: {type(game_name)}", file=sys.stderr)
        sys.exit(1)

    world_class = AutoWorldRegister.world_types.get(game_name)
    if world_class is None:
        print(f"Unknown game: {game_name!r}", file=sys.stderr)
        sys.exit(1)

    from BaseClasses import MultiWorld, PlandoOptions

    multiworld = MultiWorld(1)
    multiworld.game = {1: game_name}
    multiworld.player_name = {1: player_data.get("name", "Player")}
    multiworld.plando_options = PlandoOptions.none
    multiworld.plando_items = [[]]
    multiworld.plando_connections = [[]]
    multiworld.re_gen_passthrough = {}
    multiworld.random = rand_module.Random()

    # plando_texts was added in a later Archipelago version; set it if present.
    if hasattr(multiworld, "plando_texts"):
        multiworld.plando_texts = [[]]

    world = world_class(multiworld, 1)
    multiworld.worlds = {1: world}

    # Apply the player's options from the YAML game section so that
    # option-dependent location groups are correctly included or excluded.
    game_options_data = player_data.get(game_name) or {}
    if hasattr(world_class, "options_dataclass"):
        options = world_class.options_dataclass()
        for opt_name, opt_value in game_options_data.items():
            if not hasattr(options, opt_name):
                continue
            try:
                opt_type = type(getattr(options, opt_name))
                setattr(options, opt_name, opt_type.from_any(opt_value))
            except Exception:
                pass
        world.options = options

    world.generate_early()
    world.create_regions()

    print(len(multiworld.get_locations(1)))


if __name__ == "__main__":
    main()
