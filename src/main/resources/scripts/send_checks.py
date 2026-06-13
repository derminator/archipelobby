"""Connects to Archipelago MultiServer via WebSocket and sends location checks."""
import asyncio
import json
import sys

archipelago_dir = sys.argv[1]
host = sys.argv[2]
port = int(sys.argv[3])
slot_name = sys.argv[4]
location_ids = [int(x) for x in sys.argv[5].split(",") if x.strip()]

sys.path.insert(0, archipelago_dir)
import websockets


async def send_checks():
    uri = f"ws://{host}:{port}"
    async with asyncio.timeout(10):
        async with websockets.connect(uri, ping_timeout=None, ping_interval=None) as ws:
            room_info_raw = await ws.recv()
            room_info = json.loads(room_info_raw)

            connect_packet = [{
                "cmd": "Connect",
                "password": "",
                "name": slot_name,
                "game": "",
                "uuid": "archipelobby-check",
                "version": {"major": 0, "minor": 5, "build": 1, "class": "Version"},
                "items_handling": 0,
                "tags": ["TextOnly"],
                "slot_data": False,
            }]
            await ws.send(json.dumps(connect_packet))

            response_raw = await ws.recv()
            response = json.loads(response_raw)
            connected = False
            for packet in response:
                if packet.get("cmd") == "ConnectionRefused":
                    errors = packet.get("errors", [])
                    return {"success": False, "error": f"Connection refused: {', '.join(errors)}"}
                if packet.get("cmd") == "Connected":
                    connected = True
            if not connected:
                return {"success": False, "error": "Did not receive Connected response"}

            check_packet = [{"cmd": "LocationChecks", "locations": location_ids}]
            await ws.send(json.dumps(check_packet))

            try:
                await asyncio.wait_for(ws.recv(), timeout=2)
            except Exception:
                pass

    return {"success": True, "checked_count": len(location_ids)}


try:
    result = asyncio.run(send_checks())
    print(json.dumps(result))
except Exception as e:
    print(json.dumps({"success": False, "error": str(e)}))
