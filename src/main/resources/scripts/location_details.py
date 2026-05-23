"""Reads Archipelago multidata and save files, outputs per-slot location details as JSON."""
import json
import sys
import zlib

archipelago_dir = sys.argv[1]
multidata_path = sys.argv[2]
save_path = sys.argv[3]
target_slot = int(sys.argv[4])

sys.path.insert(0, archipelago_dir)
from Utils import restricted_loads

try:
    with open(multidata_path, "rb") as f:
        raw = f.read()
    multidata = restricted_loads(zlib.decompress(raw[1:]))
except Exception as e:
    print(json.dumps({"error": f"Failed to read multidata: {e}"}))
    sys.exit(1)

try:
    with open(save_path, "rb") as f:
        save = restricted_loads(zlib.decompress(f.read()))
except Exception as e:
    print(json.dumps({"error": f"Failed to read save: {e}"}))
    sys.exit(1)

slot_info = multidata.get("slot_info", {})
slot_locations = multidata.get("locations", {}).get(target_slot, {})
datapackage = multidata.get("datapackage", {})
checked_ids = save.get("location_checks", {}).get((0, target_slot), set())

info = slot_info.get(target_slot)
if info is None:
    print(json.dumps({"error": f"Slot {target_slot} not found"}))
    sys.exit(1)

game = info.game

id_to_name = {}
game_data = datapackage.get(game, {})
for name, loc_id in game_data.get("location_name_to_id", {}).items():
    id_to_name[loc_id] = name

locations = []
for loc_id in sorted(slot_locations.keys()):
    locations.append({
        "id": int(loc_id),
        "name": id_to_name.get(loc_id, f"Location {loc_id}"),
        "checked": loc_id in checked_ids,
    })

print(json.dumps({
    "slot": target_slot,
    "game": game,
    "locations": locations,
}))
