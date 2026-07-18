import os
import sys
import types
import unittest
from unittest.mock import patch

import multiserver_wrapper


class MultiServerWrapperTest(unittest.TestCase):
    def test_passes_multidata_to_multiserver_as_positional_argument(self):
        parsed_argv = None
        parsed_args = object()
        main_args = None

        multi_server = types.ModuleType("MultiServer")

        def parse_args():
            nonlocal parsed_argv
            parsed_argv = sys.argv.copy()
            return parsed_args

        async def main(args):
            nonlocal main_args
            main_args = args

        multi_server.parse_args = parse_args
        multi_server.main = main

        wrapper_argv = [
            "multiserver_wrapper.py",
            "--spring-url", "http://localhost:8080",
            "--room-id", "1",
            "--archipelago-dir", "Archipelago",
            "--port", "38281",
            "--host", "127.0.0.1",
        ]

        with (
            patch.object(sys, "argv", wrapper_argv),
            patch.object(sys, "path", sys.path.copy()),
            patch.dict(os.environ, {"ARCHIPELOBBY_SPRING_TOKEN": "test-token"}),
            patch.dict(sys.modules, {"MultiServer": multi_server}),
            patch.object(multiserver_wrapper.tempfile, "mkdtemp", return_value="/tmp/archipelobby-test"),
            patch.object(multiserver_wrapper.atexit, "register"),
            patch.object(multiserver_wrapper, "fetch_game_data"),
            patch.object(multiserver_wrapper, "install_save_hooks"),
        ):
            multiserver_wrapper.main()

        self.assertEqual(
            parsed_argv,
            [
                "multiserver_wrapper.py",
                os.path.join("/tmp/archipelobby-test", "game.archipelago"),
                "--port", "38281",
                "--host", "127.0.0.1",
            ],
        )
        self.assertIs(main_args, parsed_args)


if __name__ == "__main__":
    unittest.main()
