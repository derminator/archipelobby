import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


LIST_GAMES_PATH = Path(__file__).with_name("list_games.py")


class ListGamesTest(unittest.TestCase):
    def test_core_game_listing_updates_requirements_non_interactively(self):
        module_update = types.ModuleType("ModuleUpdate")
        module_update.update = Mock()

        worlds = types.ModuleType("worlds")
        auto_world = types.ModuleType("worlds.AutoWorld")
        auto_world.AutoWorldRegister = types.SimpleNamespace(world_types={"Factorio": object()})

        spec = importlib.util.spec_from_file_location("list_games_under_test", LIST_GAMES_PATH)
        list_games = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        with patch.dict(
            sys.modules,
            {
                "ModuleUpdate": module_update,
                "worlds": worlds,
                "worlds.AutoWorld": auto_world,
            },
        ):
            spec.loader.exec_module(list_games)
            self.assertEqual(["Factorio"], list_games._list_core_games())

        module_update.update.assert_called_once_with(yes=True)
