import sys
import types
import unittest
from unittest.mock import patch

import list_games


class ListGamesTest(unittest.TestCase):
    def test_core_listing_updates_dependencies_without_prompt(self):
        update_calls = []
        module_update = types.ModuleType("ModuleUpdate")
        module_update.update = lambda **kwargs: update_calls.append(kwargs)

        worlds = types.ModuleType("worlds")
        auto_world = types.ModuleType("worlds.AutoWorld")
        auto_world.AutoWorldRegister = types.SimpleNamespace(world_types={"Zelda": object(), "Factorio": object()})

        with patch.dict(
            sys.modules,
            {"ModuleUpdate": module_update, "worlds": worlds, "worlds.AutoWorld": auto_world},
        ):
            games = list_games._list_core_games()

        self.assertEqual(["Factorio", "Zelda"], games)
        self.assertEqual([{"yes": True}], update_calls)
