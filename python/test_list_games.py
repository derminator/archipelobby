import sys
import types
import unittest
from unittest.mock import patch

import list_games


class ListCoreGamesTest(unittest.TestCase):
    def test_installs_missing_dependencies_without_prompting(self):
        update_arguments = []

        module_update = types.ModuleType("ModuleUpdate")

        def update(*, yes=False):
            update_arguments.append(yes)
            raise RuntimeError("stop after checking update arguments")

        module_update.update = update

        with patch.dict(sys.modules, {"ModuleUpdate": module_update}):
            with self.assertRaisesRegex(RuntimeError, "stop after checking update arguments"):
                list_games._list_core_games()

        self.assertEqual([True], update_arguments)
