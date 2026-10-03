"""Versioned JSON saves, preserving the types required by MultiServer.set_save.

All dictionaries are tagged entry lists, so tuple/integer keys and user data
that resembles a type tag round-trip without ambiguity. Class names are resolved
only through a fixed allowlist; saved input can never trigger an import.
"""
import enum
import json


FORMAT_VERSION = 1


class SaveCodec:
    def __init__(self):
        from NetUtils import (
            ClientStatus, Hint, HintStatus, NetworkItem, NetworkPlayer,
            NetworkSlot, Permission, SlotType,
        )
        from Utils import Version

        self.named_tuples = {
            cls.__name__: cls for cls in (Hint, NetworkItem, NetworkPlayer, NetworkSlot, Version)
        }
        self.enums = {
            cls.__name__: cls for cls in (ClientStatus, HintStatus, Permission, SlotType)
        }

    def encode(self, value):
        if isinstance(value, enum.Enum):
            if self.enums.get(type(value).__name__) is not type(value):
                raise ValueError(f"Unsupported save enum: {type(value).__name__}")
            return {"type": "enum", "class": type(value).__name__, "value": value.value}
        if value is None or type(value) in (str, int, float, bool):
            return value
        if isinstance(value, tuple) and hasattr(value, "_fields"):
            if self.named_tuples.get(type(value).__name__) is not type(value):
                raise ValueError(f"Unsupported save tuple: {type(value).__name__}")
            return {
                "type": "namedtuple", "class": type(value).__name__,
                "items": [self.encode(item) for item in value],
            }
        if isinstance(value, dict):
            return {"type": "dict", "items": [
                [self.encode(key), self.encode(item)] for key, item in value.items()
            ]}
        if type(value) is list:
            return [self.encode(item) for item in value]
        if type(value) in (tuple, set, frozenset):
            return {"type": type(value).__name__, "items": [self.encode(item) for item in value]}
        raise ValueError(f"Unsupported save value: {type(value).__name__}")

    def decode(self, value):
        if value is None or type(value) in (str, int, float, bool):
            return value
        if type(value) is list:
            return [self.decode(item) for item in value]
        if type(value) is not dict:
            raise ValueError("Invalid encoded save value")
        kind = value.get("type")
        if kind == "enum":
            cls = self.enums.get(value.get("class"))
            if cls is None or set(value) != {"type", "class", "value"} or type(value["value"]) is not int:
                raise ValueError("Invalid save enum")
            return cls(value["value"])
        expected_keys = {"type", "class", "items"} if kind == "namedtuple" else {"type", "items"}
        if set(value) != expected_keys or type(value.get("items")) is not list:
            raise ValueError("Invalid save container")
        if kind == "dict":
            result = {}
            for pair in value["items"]:
                if type(pair) is not list or len(pair) != 2:
                    raise ValueError("Invalid save dictionary entry")
                key = self.decode(pair[0])
                if key in result:
                    raise ValueError("Duplicate save dictionary key")
                result[key] = self.decode(pair[1])
            return result
        items = [self.decode(item) for item in value["items"]]
        if kind == "namedtuple":
            cls = self.named_tuples.get(value.get("class"))
            if cls is None or len(items) != len(cls._fields):
                raise ValueError("Invalid save named tuple")
            return cls(*items)
        constructors = {"tuple": tuple, "set": set, "frozenset": frozenset}
        if kind not in constructors:
            raise ValueError("Unknown save type tag")
        return constructors[kind](items)

    def dumps(self, context):
        encoded_state = self.encode(context.get_save())
        # Build the projection from the captured encoding, rather than references
        # to mutable live collections that might change during an autosave.
        state = self.decode(encoded_state)
        players = []
        for slot, info in sorted(context.slot_info.items()):
            if int(info.type) != 1:
                continue
            players.append({
                "slot": slot, "name": info.name, "game": info.game,
                "checksDone": len(state["location_checks"].get((0, slot), set())),
                "checksTotal": len(context.locations.get(slot, {})),
                "statusCode": int(state["client_game_state"].get((0, slot), 0)),
            })
        envelope = {
            "formatVersion": FORMAT_VERSION, "state": encoded_state,
            "tracker": {"players": players},
        }
        return json.dumps(envelope, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")

    def loads(self, payload):
        def reject_constant(value):
            raise ValueError(f"Invalid JSON constant: {value}")

        envelope = json.loads(payload.decode("utf-8"), parse_constant=reject_constant)
        if (type(envelope) is not dict or type(envelope.get("formatVersion")) is not int
                or envelope["formatVersion"] != FORMAT_VERSION):
            raise ValueError("Unsupported JSON save format")
        if type(envelope.get("tracker")) is not dict or type(envelope["tracker"].get("players")) is not list:
            raise ValueError("Missing tracker projection")
        state = self.decode(envelope["state"])
        if type(state) is not dict:
            raise ValueError("Invalid save state")
        return state
