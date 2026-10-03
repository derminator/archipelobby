"""
Wrapper around Archipelago's MultiServer that exchanges multidata and save
bytes with the surrounding Spring app over HTTP, instead of reading and
writing files on disk.

The wrapper:
  - Downloads the .archipelago multidata for a room from Spring at startup,
    writes it to a temp file (MultiServer.load() expects a file path),
  - Monkey-patches MultiServer.Context._save to PUT a versioned JSON save
    with tracker metadata to Spring instead of writing a .apsave file,
  - Monkey-patches MultiServer.Context.init_save to GET that blob from
    Spring instead of reading it from disk.

The temp file is deleted on exit. Arguments after `--` are forwarded to
MultiServer.parse_args() unchanged.
"""
import argparse
import asyncio
import atexit
import datetime
import os
import shutil
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

from save_codec import SaveCodec

HTTP_TIMEOUT = 30  # seconds


def _authed_request(url: str, token: str, *, data=None, method=None, content_type=None):
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    return urllib.request.Request(url, data=data, method=method, headers=headers)


def fetch_game_data(base_url: str, token: str, room_id: int, target_path: str) -> None:
    req = _authed_request(f"{base_url}/internal/multiserver/game/{room_id}", token)
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        data = resp.read()
    with open(target_path, "wb") as f:
        f.write(data)


def install_save_hooks(base_url: str, token: str, room_id: int, multi_server) -> None:
    codec = SaveCodec()

    save_url = f"{base_url}/internal/multiserver/save/{room_id}"

    def _save(self, *_) -> bool:
        try:
            payload = codec.dumps(self)
        except Exception as e:
            self.save_dirty = True
            self.logger.exception(e)
            return False
        try:
            req = _authed_request(
                save_url, token,
                data=payload, method="PUT",
                content_type="application/json",
            )
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT):
                pass
            return True
        except Exception as e:
            self.save_dirty = True
            if isinstance(e, urllib.error.HTTPError):
                e.close()
            self.logger.exception(e)
            return False

    def _start_async_saving(self, atexit_save: bool = True) -> None:
        if self.auto_saver_thread:
            return

        def save_regularly():
            second = multi_server.get_saving_second(self.seed_name, self.auto_save_interval)
            while not self.exit_event.is_set():
                now = datetime.datetime.now()
                next_wakeup = (second - now.second - now.microsecond * 0.000001) % self.auto_save_interval
                time.sleep(max(1.0, next_wakeup))
                if self.exit_event.is_set():
                    break
                if self.save_dirty:
                    # Clear before capturing the save, so gameplay updates during
                    # encoding or the HTTP write stay pending for the next tick.
                    self.save_dirty = False
                    self.logger.debug("Saving via thread.")
                    if not self._save():
                        self.save_dirty = True
                        self.logger.info("Saving failed. Retry in %s seconds.", self.auto_save_interval)
            if not atexit_save:
                multi_server.queue_gc()

        self.auto_saver_thread = threading.Thread(target=save_regularly, daemon=True)
        self.auto_saver_thread.start()
        if atexit_save:
            atexit.register(self._save, True)

    def init_save(self, enabled: bool = True) -> None:
        self.saving = enabled
        if not self.saving:
            return
        try:
            req = _authed_request(save_url, token)
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
                save_bytes = resp.read()
            save_data = codec.loads(save_bytes)
            self.set_save(save_data)
        except urllib.error.HTTPError as e:
            e.close()
            if e.code != 404:
                # Anything other than "no save yet" (404) is a real failure. Fail
                # loudly instead of silently starting with an empty save and
                # overwriting the player's progress on the next autosave.
                raise
            self.logger.error("No save data found, starting a new game")
            if not self._save():
                raise RuntimeError("Failed to persist initial JSON save")
        self._start_async_saving()

    multi_server.Context._save = _save
    multi_server.Context._start_async_saving = _start_async_saving
    multi_server.Context.init_save = init_save


def main() -> None:
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("--spring-url", required=True)
    parser.add_argument("--room-id", required=True, type=int)
    parser.add_argument("--archipelago-dir", required=True)
    args, rest = parser.parse_known_args()

    # Read the token from the environment so it doesn't leak via ps/argv.
    token = os.environ.get("ARCHIPELOBBY_SPRING_TOKEN")
    if not token:
        print("ARCHIPELOBBY_SPRING_TOKEN env var is required", file=sys.stderr)
        sys.exit(1)

    sys.path.insert(0, os.path.abspath(args.archipelago_dir))

    tmpdir = tempfile.mkdtemp(prefix="archipelobby-")

    def _cleanup_tmpdir():
        try:
            shutil.rmtree(tmpdir)
        except OSError as e:
            print(f"cleanup: failed to remove {tmpdir}: {e}", file=sys.stderr)

    atexit.register(_cleanup_tmpdir)
    data_path = os.path.join(tmpdir, "game.archipelago")
    fetch_game_data(args.spring_url, token, args.room_id, data_path)

    import MultiServer
    install_save_hooks(args.spring_url, token, args.room_id, MultiServer)

    # MultiServer defines the multidata file as its optional positional
    # argument, not as a --multidata option.
    sys.argv = [sys.argv[0], data_path] + rest
    asyncio.run(MultiServer.main(MultiServer.parse_args()))


if __name__ == "__main__":
    main()
