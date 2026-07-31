"""Reads Archipelago multidata and save files, outputs player progress as JSON."""
import json
import sys
import zlib

archipelago_dir = sys.argv[1]
multidata_path = sys.argv[2]
save_path = sys.argv[3]

sys.path.insert(0, archipelago_dir)
from Utils import restricted_loads

STATUS_NAMES = {
    0: "Disconnected",
    5: "Connected",
    10: "Ready",
    20: "Playing",
    30: "Goal Completed",
}

try:
    with open(multidata_path, "rb") as f:
        raw = f.read()
    multidata = restricted_loads(zlib.decompress(raw[1:]))
except Exception as e:
    print(json.dumps({"players": [], "error": f"Failed to read multidata: {e}"}))
    sys.exit(0)

try:
    with open(save_path, "rb") as f:
        save = restricted_loads(zlib.decompress(f.read()))
except Exception as e:
    print(json.dumps({"players": [], "error": f"Failed to read save: {e}"}))
    sys.exit(0)

location_checks = save.get("location_checks", {})
client_game_state = save.get("client_game_state", {})

slot_info = multidata.get("slot_info", {})
locations = multidata.get("locations", {})

players = []
for slot, info in sorted(slot_info.items(), key=lambda x: int(x[0])):
    if int(info.type) != 1:
        continue
    total = len(locations.get(slot, {}))
    checked = len(location_checks.get((0, slot), set()))
    status_code = client_game_state.get((0, slot), 0)
    players.append({
        "slot": int(slot),
        "name": info.name,
        "game": info.game,
        "checks_done": checked,
        "checks_total": total,
        "status": STATUS_NAMES.get(int(status_code), "Unknown"),
    })

print(json.dumps({"players": players}))
