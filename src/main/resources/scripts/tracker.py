"""Fetch Archipelago game and save state from Spring without storing either on disk."""
import json
import os
import sys
import urllib.error
import urllib.request
import zlib

archipelago_dir = sys.argv[1]
base_url = sys.argv[2].rstrip("/")
room_id = int(sys.argv[3])
token = os.environ["ARCHIPELOBBY_SPRING_TOKEN"]

sys.path.insert(0, archipelago_dir)
from Utils import restricted_loads

STATUS_NAMES = {
    0: "Disconnected",
    5: "Connected",
    10: "Ready",
    20: "Playing",
    30: "Goal Completed",
}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        # A redirected Authorization header would disclose the internal bearer token.
        raise urllib.error.HTTPError(request.full_url, code, msg, headers, fp)


# The internal bearer token must not be forwarded to an inherited HTTP(S) proxy.
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def fetch(kind):
    request = urllib.request.Request(
        f"{base_url}/internal/multiserver/{kind}/{room_id}",
        headers={"Authorization": f"Bearer {token}"},
    )
    with opener.open(request, timeout=30) as response:
        return response.read()


try:
    raw = fetch("game")
    multidata = restricted_loads(zlib.decompress(raw[1:]))
except Exception as e:
    print(json.dumps({"players": [], "error": f"Failed to read multidata: {e}"}))
    sys.exit(0)

try:
    try:
        raw_save = fetch("save")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        # Newly generated games have no persisted save yet.
        raw_save = None
    save = restricted_loads(zlib.decompress(raw_save)) if raw_save is not None else {}
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
