import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import list_games


class ListGamesTest(unittest.TestCase):
    def test_core_game_listing_updates_requirements_noninteractively(self):
        update_calls = []
        module_update = types.ModuleType("ModuleUpdate")
        module_update.update = lambda **kwargs: update_calls.append(kwargs)

        worlds = types.ModuleType("worlds")
        auto_world = types.ModuleType("worlds.AutoWorld")
        auto_world.AutoWorldRegister = types.SimpleNamespace(
            world_types={"Factorio": object(), "A Link to the Past": object()},
        )

        with patch.dict(
            sys.modules,
            {
                "ModuleUpdate": module_update,
                "worlds": worlds,
                "worlds.AutoWorld": auto_world,
            },
        ):
            self.assertEqual(
                list_games._list_core_games(),
                ["A Link to the Past", "Factorio"],
            )

        self.assertEqual(update_calls, [{"yes": True}])

    def test_docker_image_installs_archipelago_requirements(self):
        dockerfile = Path(__file__).parent.parent / "Dockerfile"

        self.assertIn(
            "/app/.venv/bin/python /app/Archipelago/ModuleUpdate.py --yes",
            dockerfile.read_text(),
        )


if __name__ == "__main__":
    unittest.main()
