"""Exercise the tracker against the same internal HTTP endpoints as MultiServer."""
import http.server
import json
import os
import pickle
import subprocess
import sys
import tempfile
import threading
import unittest
import zlib
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).resolve().parents[1] / "src/main/resources/scripts/tracker.py"


class TrackerTest(unittest.TestCase):
    def setUp(self):
        self.workdir = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.workdir.cleanup)
        archipelago_dir = Path(self.workdir.name)
        (archipelago_dir / "Utils.py").write_text("import pickle\nrestricted_loads = pickle.loads\n")
        multidata = {
            "slot_info": {1: SimpleNamespace(type=1, name="Alice", game="Minecraft")},
            "locations": {1: {101: None, 102: None}},
        }
        self.game = b"0" + zlib.compress(pickle.dumps(multidata))
        self.save = zlib.compress(pickle.dumps({
            "location_checks": {(0, 1): {101}},
            "client_game_state": {(0, 1): 20},
        }))
        self.redirect_url = None
        testcase = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer test-token":
                    self.send_error(404)
                    return
                if self.path == "/internal/multiserver/game/42" and testcase.redirect_url:
                    self.send_response(302)
                    self.send_header("Location", testcase.redirect_url)
                    self.end_headers()
                    return
                if self.path == "/internal/multiserver/game/42":
                    status, data = 200, testcase.game
                elif self.path == "/internal/multiserver/save/42":
                    status, data = (200, testcase.save) if testcase.save is not None else (404, b"")
                else:
                    status, data = 404, b""
                self.send_response(status)
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{server.server_port}"
        self.archipelago_dir = archipelago_dir

    def run_tracker(self, extra_env=None):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(self.archipelago_dir), self.url, "42"],
            env={**os.environ, "ARCHIPELOBBY_SPRING_TOKEN": "test-token", "PYTHONDONTWRITEBYTECODE": "1",
                 **(extra_env or {})},
            capture_output=True, text=True, check=True,
        )
        return json.loads(result.stdout)

    def test_fetches_game_and_save_without_writing_them_to_disk(self):
        data = self.run_tracker()
        self.assertEqual(data["players"], [{
            "slot": 1, "name": "Alice", "game": "Minecraft",
            "checks_done": 1, "checks_total": 2, "status": "Playing",
        }])
        self.assertEqual(list(self.archipelago_dir.iterdir()), [self.archipelago_dir / "Utils.py"])

    def test_new_game_without_save_shows_players_with_zero_checks(self):
        self.save = None
        data = self.run_tracker()
        self.assertEqual(data["players"][0]["checks_done"], 0)
        self.assertEqual(data["players"][0]["status"], "Disconnected")

    def test_corrupt_game_reports_error_instead_of_empty_tracker(self):
        self.game = b"invalid"
        data = self.run_tracker()
        self.assertIn("error", data)
        self.assertEqual(data["players"], [])

    def test_corrupt_save_reports_error_instead_of_empty_tracker(self):
        self.save = b"invalid"
        data = self.run_tracker()
        self.assertIn("error", data)
        self.assertEqual(data["players"], [])

    def test_redirect_cannot_forward_the_internal_bearer_token(self):
        received_auth = []

        class RedirectTarget(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received_auth.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"unexpected redirect")

            def log_message(self, *_):
                pass

        target = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RedirectTarget)
        self.addCleanup(target.server_close)
        self.addCleanup(target.shutdown)
        threading.Thread(target=target.serve_forever, daemon=True).start()
        self.redirect_url = f"http://127.0.0.1:{target.server_port}/steal"

        self.assertIn("error", self.run_tracker())
        self.assertEqual(received_auth, [])

    def test_inherited_http_proxy_cannot_receive_the_internal_bearer_token(self):
        received_auth = []

        class Proxy(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received_auth.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"proxy must not receive this request")

            def log_message(self, *_):
                pass

        proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        self.addCleanup(proxy.server_close)
        self.addCleanup(proxy.shutdown)
        threading.Thread(target=proxy.serve_forever, daemon=True).start()

        data = self.run_tracker({
            "HTTP_PROXY": f"http://127.0.0.1:{proxy.server_port}",
            "http_proxy": f"http://127.0.0.1:{proxy.server_port}",
            "NO_PROXY": "", "no_proxy": "",
        })
        self.assertEqual(received_auth, [])
        self.assertEqual(data["players"][0]["name"], "Alice")


if __name__ == "__main__":
    unittest.main()
