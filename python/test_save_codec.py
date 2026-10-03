import datetime
import enum
import json
import sys
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from save_codec import SaveCodec


# Use the bundled server's real types and get_save/set_save implementation,
# without letting ModuleUpdate install packages during a test run.
with (
    patch.object(sys, "path", [str(Path(__file__).resolve().parents[1] / "Archipelago"), *sys.path]),
    patch.dict("os.environ", {"SKIP_REQUIREMENTS_UPDATE": "1"}),
):
    import MultiServer
    from NetUtils import ClientStatus, Hint, HintStatus, NetworkItem, NetworkPlayer, NetworkSlot, Permission, SlotType
    from Utils import Version


def make_context(cls=MultiServer.Context, populated=True):
    # The catalog is unrelated to persistence and imports every installed world.
    with patch.object(cls, "_load_game_data"):
        context = cls("127.0.0.1", 38281, None, None, 1, 10, True, logger=Mock())
    context.slot_info = {
        2: NetworkSlot("Bob", "Factorio", SlotType.player),
        1: NetworkSlot("Alice", "Minecraft", SlotType.player),
        3: NetworkSlot("Group", "Minecraft", SlotType.group, (1, 2)),
        4: NetworkSlot("Spectator", "Archipelago", SlotType.spectator),
    }
    context.locations = {1: {101: None, 102: None}, 2: {201: None}}
    context.connect_names = {"Alice": (0, 1), "Bob": (0, 2)}
    if populated:
        context.received_items = {(0, 1, True): [NetworkItem(301, 201, 2, 1)]}
        context.location_checks[0, 1] = {101}
        context.client_game_state[0, 1] = ClientStatus.CLIENT_PLAYING
        context.hints[0, 1] = {Hint(2, 1, 102, 302, False, "Door", 1, HintStatus.HINT_PRIORITY)}
        context.hints_used[0, 1] = 3
        context.name_aliases[0, 1] = "Alias"
        now = datetime.datetime(2026, 10, 3, 12, 30, tzinfo=datetime.timezone.utc)
        context.client_activity_timers[0, 1] = now
        context.client_connection_timers[0, 1] = now
        context.group_collected = {3: {1, 2}}
        context.stored_data = {
            "nested": {"type": "dict", "items": [1, True, None], 5: {(0, 2): frozenset({3, 4})}},
            "slot": context.slot_info[3],
            "player": NetworkPlayer(0, 1, "Alias", "Alice"),
            "version": Version(0, 6, 7),
            "permission": Permission.auto_enabled,
            "largeInteger": 2 ** 100,
        }
        context.random.seed(42)
        context.random.gauss(0, 1)  # also exercise the cached Gaussian state
        context.password = "room-password"
        context.server_password = "admin-password"
        context.release_mode = "goal"
        context.collect_mode = "auto"
        context.remaining_mode = "enabled"
        context.countdown_mode = "manual"
        context.item_cheat = False
    return context


class SaveCodecTest(unittest.TestCase):
    def setUp(self):
        self.codec = SaveCodec()

    def test_complete_save_restores_real_server_state_and_random_sequence(self):
        original = make_context()
        payload = self.codec.dumps(original)
        restored = make_context(populated=False)
        restored.set_save(self.codec.loads(payload))

        self.assertEqual(original.get_save(), restored.get_save())
        self.assertEqual(original.client_activity_timers, restored.client_activity_timers)
        self.assertEqual(original.client_connection_timers, restored.client_connection_timers)
        self.assertIs(type(next(iter(restored.received_items))), tuple)
        self.assertIs(type(restored.received_items[0, 1, True][0]), NetworkItem)
        self.assertIs(type(restored.location_checks[0, 1]), set)
        hint = next(iter(restored.hints[0, 1]))
        self.assertIs(type(hint), Hint)
        self.assertIs(type(hint.status), HintStatus)
        self.assertIn(hint, restored.hints[0, 1])
        self.assertIs(type(restored.client_game_state[0, 1]), ClientStatus)
        self.assertIs(type(restored.stored_data["slot"]), NetworkSlot)
        self.assertIs(type(restored.stored_data["slot"].type), SlotType)
        self.assertIs(type(restored.stored_data["player"]), NetworkPlayer)
        self.assertIs(type(restored.stored_data["version"]), Version)
        self.assertIs(type(restored.stored_data["permission"]), Permission)
        self.assertIs(type(restored.stored_data["nested"][5][0, 2]), frozenset)
        self.assertEqual([original.random.random() for _ in range(5)], [restored.random.random() for _ in range(5)])
        self.assertEqual(original.random.gauss(0, 1), restored.random.gauss(0, 1))

    def test_projection_filters_sorts_and_defaults_players(self):
        envelope = json.loads(self.codec.dumps(make_context()))
        self.assertEqual(envelope["formatVersion"], 1)
        self.assertEqual(envelope["tracker"]["players"], [
            {"slot": 1, "name": "Alice", "game": "Minecraft", "checksDone": 1, "checksTotal": 2, "statusCode": 20},
            {"slot": 2, "name": "Bob", "game": "Factorio", "checksDone": 0, "checksTotal": 1, "statusCode": 0},
        ])

    def test_projection_uses_the_captured_state(self):
        context = make_context()
        encode = self.codec.encode

        def capture_then_change(state):
            encoded = encode(state)
            if state is saved:
                context.location_checks[0, 1].add(102)
            return encoded

        saved = context.get_save()
        context.get_save = lambda: saved
        with patch.object(self.codec, "encode", side_effect=capture_then_change):
            envelope = json.loads(self.codec.dumps(context))
        self.assertEqual(envelope["tracker"]["players"][0]["checksDone"], 1)
        self.assertEqual(self.codec.decode(envelope["state"])["location_checks"][0, 1], {101})

    def encode_while_mutating(self, context, marker, mutate):
        encoding = threading.Event()
        changed = threading.Event()

        def change_live_state():
            if encoding.wait(5):
                mutate()
                changed.set()

        encode = self.codec.encode

        def encode_with_concurrent_change(value):
            if type(value) is type(marker) and value == marker and not encoding.is_set():
                encoding.set()
                self.assertTrue(changed.wait(5), "Live-state mutation did not complete")
            return encode(value)

        worker = threading.Thread(target=change_live_state, daemon=True)
        worker.start()
        try:
            with patch.object(self.codec, "encode", side_effect=encode_with_concurrent_change):
                envelope = json.loads(self.codec.dumps(context))
        finally:
            encoding.set()
            worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertTrue(changed.is_set())
        return envelope

    def test_live_checks_can_change_while_a_save_is_encoded(self):
        context = make_context(populated=False)
        checks = {101}
        context.location_checks[0, 1] = checks
        envelope = self.encode_while_mutating(context, 101, lambda: checks.add(102))
        state = self.codec.decode(envelope["state"])
        self.assertEqual(state["location_checks"][0, 1], {101})
        self.assertEqual(envelope["tracker"]["players"][0]["checksDone"], 1)
        self.assertEqual(checks, {101, 102})

    def test_live_dictionary_can_change_while_a_save_is_encoded(self):
        context = make_context(populated=False)
        stored_data = {"first": "capture-me"}
        context.stored_data = stored_data
        envelope = self.encode_while_mutating(context, "capture-me", lambda: stored_data.update(second="later"))
        state = self.codec.decode(envelope["state"])
        self.assertEqual(state["stored_data"], {"first": "capture-me"})
        self.assertEqual(stored_data, {"first": "capture-me", "second": "later"})

    def test_live_list_can_change_while_a_save_is_encoded(self):
        context = make_context(populated=False)
        values = ["capture-me"]
        context.stored_data["list"] = values
        envelope = self.encode_while_mutating(context, "capture-me", lambda: values.append("later"))
        state = self.codec.decode(envelope["state"])
        self.assertEqual(state["stored_data"]["list"], ["capture-me"])
        self.assertEqual(values, ["capture-me", "later"])

    def test_corrupt_unknown_and_unsupported_formats_are_rejected(self):
        envelope = json.loads(self.codec.dumps(make_context()))
        invalid = [b"not-json", b"null", b"{}", b'{"formatVersion":NaN}']
        for version in (2, True, "1"):
            invalid.append(json.dumps({**envelope, "formatVersion": version}).encode())
        invalid.append(json.dumps({**envelope, "state": {"type": "pickle", "items": []}}).encode())
        invalid.append(json.dumps({**envelope, "state": {"type": "namedtuple", "class": "os.system", "items": []}}).encode())
        invalid.append(json.dumps({**envelope, "tracker": {}}).encode())
        for payload in invalid:
            with self.subTest(payload=payload[:80]), self.assertRaises((ValueError, KeyError)):
                self.codec.loads(payload)

    def test_unknown_objects_and_enums_are_rejected(self):
        class Unknown(enum.IntEnum):
            VALUE = 1

        for value in (object(), b"bytes", Unknown.VALUE):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.codec.encode(value)
        context = make_context()
        context.stored_data["bad"] = float("nan")
        with self.assertRaises(ValueError):
            self.codec.dumps(context)

    def test_multiserver_checks_save_version_and_multiworld(self):
        context = make_context()
        state = self.codec.loads(self.codec.dumps(context))
        for field, value in (("version", context.save_version + 1), ("connect_names", {"Other": (0, 1)})):
            with self.subTest(field=field), self.assertRaises(Exception):
                make_context(populated=False).set_save({**state, field: value})


if __name__ == "__main__":
    unittest.main()
