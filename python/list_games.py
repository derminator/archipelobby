"""List Archipelago games registered in the current installation.

Invoked by GameCatalogService to list the core games that ship with the
Archipelago submodule. Emits a JSON payload between sentinel markers so the
Kotlin caller can parse it out of the merged stdout/log stream.

Per-apworld game extraction lives on the Kotlin side (direct zip read of the
archipelago.json manifest), so this script only implements core-list mode.
"""

import json
import sys
import traceback

SENTINEL_START = "<<<ARCHIPELOBBY_GAMES_JSON>>>"
SENTINEL_END = "<<<END>>>"


def _emit(payload):
    print(SENTINEL_START, flush=True)
    print(json.dumps(payload), flush=True)
    print(SENTINEL_END, flush=True)


def _list_core_games():
    import ModuleUpdate
    ModuleUpdate.update()

    import worlds  # noqa: F401 (import side effect: populates AutoWorldRegister)
    from worlds.AutoWorld import AutoWorldRegister

    return sorted(AutoWorldRegister.world_types.keys())


def main(argv):
    mode = argv[1] if len(argv) > 1 else "core"
    if mode != "core":
        _emit({"error": f"unknown mode: {mode}"})
        return 2
    try:
        games = _list_core_games()
    except Exception as exc:
        _emit({
            "error": f"failed to enumerate core games: {exc}",
            "traceback": traceback.format_exc(),
        })
        return 1
    _emit({"games": games})
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
