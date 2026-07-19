import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import list_games


class ListGamesTest(unittest.TestCase):
    def test_docker_image_installs_archipelago_dependencies_during_build(self):
        dockerfile = (Path(__file__).parent.parent / "Dockerfile").read_text()

        self.assertIn(
            "RUN /app/.venv/bin/python /app/Archipelago/ModuleUpdate.py --yes",
            dockerfile,
        )
        self.assertLess(
            dockerfile.index("COPY ./Archipelago ./Archipelago"),
            dockerfile.index("RUN /app/.venv/bin/python /app/Archipelago/ModuleUpdate.py --yes"),
        )

    def test_core_enumeration_updates_dependencies_non_interactively(self):
        module_update = types.ModuleType("ModuleUpdate")
        update_arguments = None

        def update(**kwargs):
            nonlocal update_arguments
            update_arguments = kwargs

        module_update.update = update
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
            games = list_games._list_core_games()

        self.assertEqual({"yes": True}, update_arguments)
        self.assertEqual(["A Link to the Past", "Factorio"], games)


if __name__ == "__main__":
    unittest.main()
