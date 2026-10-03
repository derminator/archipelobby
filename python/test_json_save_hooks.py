import http.server
import json
import queue
import threading
import types
import unittest
from unittest.mock import Mock, patch

import multiserver_wrapper
from test_save_codec import MultiServer, make_context


class JsonSaveHooksTest(unittest.TestCase):
    def setUp(self):
        self.saved = None
        self.puts = []
        self.fail_put = False
        self.get_status = None
        testcase = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer test-token" or self.path != "/internal/multiserver/save/42":
                    self.send_error(404)
                    return
                status = testcase.get_status or (200 if testcase.saved is not None else 404)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                if testcase.saved is not None:
                    self.wfile.write(testcase.saved)

            def do_PUT(self):
                payload = self.rfile.read(int(self.headers["Content-Length"]))
                if self.headers.get("Authorization") != "Bearer test-token" or self.path != "/internal/multiserver/save/42":
                    self.send_error(404)
                    return
                testcase.puts.append((self.headers.get("Content-Type"), payload))
                if not testcase.fail_put:
                    testcase.saved = payload
                self.send_response(500 if testcase.fail_put else 204)
                self.end_headers()

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.context_class = type("WrappedContext", (MultiServer.Context,), {})
        multiserver_wrapper.install_save_hooks(
            f"http://127.0.0.1:{server.server_port}", "test-token", 42,
            types.SimpleNamespace(
                Context=self.context_class,
                get_saving_second=MultiServer.get_saving_second,
                queue_gc=MultiServer.queue_gc,
            ),
        )

    def new_context(self, populated=False):
        context = make_context(self.context_class, populated=populated)
        context._start_async_saving = Mock()
        return context

    def test_initial_save_autosave_exit_save_and_restart(self):
        initial = self.new_context()
        initial.init_save()
        initial._start_async_saving.assert_called_once()
        self.assertEqual(len(self.puts), 1)
        self.assertEqual(self.puts[0][0], "application/json")
        self.assertEqual(json.loads(self.saved)["tracker"]["players"][0]["checksDone"], 0)

        active = self.new_context(populated=True)
        self.assertTrue(active._save())  # background autosave uses this hook
        self.assertEqual(json.loads(self.saved)["tracker"]["players"][0]["checksDone"], 1)
        active.location_checks[0, 1].add(102)
        self.assertTrue(active._save(True))  # atexit/shutdown invokes this signature
        restored = self.new_context()
        restored.init_save()
        self.assertEqual(active.get_save(), restored.get_save())
        self.assertEqual(len(self.puts), 3)  # loading an existing save does not write

    def test_corrupt_and_unknown_saves_fail_startup_without_overwriting(self):
        for payload in (b"corrupt", b'{"formatVersion":2}', b'{"formatVersion":1,"tracker":{}}'):
            with self.subTest(payload=payload):
                self.saved = payload
                context = self.new_context()
                with self.assertRaises((ValueError, KeyError)):
                    context.init_save()
                context._start_async_saving.assert_not_called()
                self.assertEqual(self.saved, payload)
                self.assertEqual(self.puts, [])

    def test_mismatched_and_newer_saves_fail_without_overwriting(self):
        active = self.new_context(populated=True)
        self.assertTrue(active._save())
        original_payload = self.saved
        for mismatch in ("multiworld", "version"):
            with self.subTest(mismatch=mismatch):
                context = self.new_context()
                if mismatch == "multiworld":
                    context.connect_names = {"Other": (0, 1)}
                else:
                    context.save_version = 1
                with self.assertRaises(Exception):
                    context.init_save()
                context._start_async_saving.assert_not_called()
                self.assertEqual(self.saved, original_payload)
                self.assertEqual(len(self.puts), 1)

    def test_non_404_load_failure_does_not_create_a_new_save(self):
        self.get_status = 500
        context = self.new_context()
        with self.assertRaises(multiserver_wrapper.urllib.error.HTTPError):
            context.init_save()
        context._start_async_saving.assert_not_called()
        self.assertEqual(self.puts, [])

    def test_failed_initial_write_prevents_autosave_startup(self):
        self.fail_put = True
        context = self.new_context()
        with self.assertRaisesRegex(RuntimeError, "initial JSON save"):
            context.init_save()
        context._start_async_saving.assert_not_called()
        self.assertIsNone(self.saved)

    def test_failed_encoding_or_write_preserves_previous_progress(self):
        context = self.new_context(populated=True)
        self.assertTrue(context._save())
        original_payload = self.saved
        context.stored_data["unsupported"] = object()
        self.assertFalse(context._save())
        self.assertEqual(len(self.puts), 1)
        self.assertEqual(self.saved, original_payload)
        del context.stored_data["unsupported"]
        context.location_checks[0, 1].add(102)
        self.fail_put = True
        self.assertFalse(context._save())
        self.assertEqual(self.saved, original_payload)

    def test_disabled_saving_neither_loads_nor_writes(self):
        context = self.new_context()
        context.init_save(False)
        context._start_async_saving.assert_not_called()
        self.assertEqual(self.puts, [])
        self.assertIsNone(self.saved)

    def start_autosaver(self, context):
        # Exercise the actual background loop, including its dirty-state handling.
        del context._start_async_saving
        context.auto_save_interval = 1
        context.saving = True
        context._start_async_saving(atexit_save=False)

    def stop_autosaver(self, context):
        context.exit_event.set()
        context.auto_saver_thread.join(5)
        self.assertFalse(context.auto_saver_thread.is_alive())

    def assert_autosave_retries(self, failure):
        context = self.new_context(populated=True)
        self.assertTrue(context._save())
        previous = self.saved
        context.location_checks[0, 1].add(102)
        context.save_dirty = True
        if failure == "encoding":
            context.stored_data["unsupported"] = object()
        else:
            self.fail_put = True
        attempts = queue.Queue()
        save = context._save

        def record_save():
            result = save()
            attempts.put(result)
            return result

        with patch.object(context, "_save", side_effect=record_save):
            self.start_autosaver(context)
            try:
                self.assertFalse(attempts.get(timeout=5))
                self.assertEqual(self.saved, previous)
                context.stored_data.pop("unsupported", None)
                self.fail_put = False
                # No call to save(): the failed attempt must stay pending.
                self.assertTrue(attempts.get(timeout=5))
                self.assertEqual(json.loads(self.saved)["tracker"]["players"][0]["checksDone"], 2)
                self.assertFalse(context.save_dirty)
            finally:
                self.stop_autosaver(context)

    def test_autosave_retries_failed_encoding_without_new_game_events(self):
        self.assert_autosave_retries("encoding")

    def test_autosave_retries_failed_http_writes_without_new_game_events(self):
        self.assert_autosave_retries("http")

    def test_autosave_keeps_changes_made_during_a_successful_write_pending(self):
        context = self.new_context(populated=True)
        context.save_dirty = True
        attempts = queue.Queue()
        save = context._save
        first_write = True

        def save_then_update():
            nonlocal first_write
            result = save()
            if first_write:
                first_write = False
                context.location_checks[0, 1].add(102)
                context.save()
            attempts.put((result, json.loads(self.saved)["tracker"]["players"][0]["checksDone"]))
            return result

        with patch.object(context, "_save", side_effect=save_then_update):
            self.start_autosaver(context)
            try:
                self.assertEqual(attempts.get(timeout=5), (True, 1))
                self.assertEqual(attempts.get(timeout=5), (True, 2))
                self.assertFalse(context.save_dirty)
            finally:
                self.stop_autosaver(context)


if __name__ == "__main__":
    unittest.main()
