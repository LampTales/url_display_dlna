"""Small mpv JSON-IPC controller used by the local DLNA renderer.

The Windows mpv build exposes JSON IPC through a named pipe.  The controller
keeps one mpv process alive, sends ``loadfile``/transport commands, and turns
mpv's asynchronous end-of-file and property events into renderer callbacks.
It intentionally uses only the Python standard library.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wintypes
import json
import msvcrt
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid
from typing import Any, Callable


class MpvUnavailable(RuntimeError):
    """Raised when mpv cannot be started or its IPC endpoint cannot be opened."""


class MpvController:
    def __init__(
        self,
        executable: str | os.PathLike[str],
        on_state_change: Callable[[str], None] | None = None,
        on_position_change: Callable[[float | None, float | None], None] | None = None,
        on_log: Callable[[str], None] | None = None,
    ) -> None:
        self.executable = Path(executable)
        self.on_state_change = on_state_change
        self.on_position_change = on_position_change
        self.on_log = on_log or (lambda message: print(message, flush=True))
        self._process: subprocess.Popen[bytes] | None = None
        self._event_pipe: Any = None
        self._pipe_handle: int | None = None
        self._pipe_name = ""
        self._write_lock = threading.Lock()
        self._request_id = 0
        self._reader: threading.Thread | None = None
        self._stop = threading.Event()
        self._state = "STOPPED"
        # Suppress mpv's transient pause-property event while SetURI is being
        # acknowledged. Macast reports PAUSED_PLAYBACK until Play arrives.
        self._awaiting_play = False
        self._position = 0.0
        self._duration = 0.0
        self._path = ""

    @property
    def available(self) -> bool:
        return self.executable.is_file()

    @property
    def running(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def start(self) -> None:
        if self.running:
            return
        if not self.available:
            raise MpvUnavailable(f"mpv not found: {self.executable}")
        self._stop.clear()
        self._pipe_name = f"cloudmusic2ktv-mpv-{os.getpid()}-{uuid.uuid4().hex}"
        args = [
            str(self.executable),
            "--no-config",
            "--idle=yes",
            "--force-window=yes",
            "--input-terminal=no",
            "--terminal=no",
            "--title=CloudMusic2KTV Mock Renderer",
            f"--input-ipc-server={self._pipe_name}",
        ]
        try:
            self._process = subprocess.Popen(args)
            self._event_pipe = self._connect_pipe(timeout=6.0)
        except Exception as error:
            self.close()
            raise MpvUnavailable(f"unable to start mpv: {error}") from error
        self._reader = threading.Thread(target=self._reader_loop, name="mpv-ipc", daemon=True)
        for name in ("time-pos", "duration", "pause", "path"):
            self._observe_on_event_pipe(name)
        self._reader.start()
        self._log(f"mpv started (pid={self._process.pid})")

    def close(self) -> None:
        self._stop.set()
        process = self._process
        pipe = self._event_pipe
        if process is not None and process.poll() is None:
            try:
                # Let mpv close its side of the pipe before closing the Python
                # file object.  Closing a Windows pipe while a reader thread
                # is blocked can otherwise wait indefinitely.
                self._send(["quit"], wait=False)
            except (OSError, ValueError):
                pass
            try:
                process.terminate()
                process.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                except OSError:
                    pass
        self._event_pipe = None
        self._process = None
        if pipe is not None:
            try:
                # os.close bypasses BufferedIOBase's cross-thread flush/close
                # coordination, which can block on a named-pipe reader.
                if hasattr(pipe, "detach"):
                    raw = pipe.detach()
                    os.close(raw.fileno())
                else:
                    pipe.close()
            except (OSError, ValueError):
                pass
        self._pipe_handle = None

    def set_uri(self, uri: str) -> None:
        self.start()
        self._path = uri
        self._position = 0.0
        self._duration = 0.0
        # Match Macast's handshake: load immediately, but report paused until
        # the sender confirms the cast session with Play.
        self._awaiting_play = True
        self._state = "PAUSED_PLAYBACK"
        self._send(["loadfile", uri, "replace"], wait=False)

    def play(self) -> None:
        self.start()
        self._awaiting_play = False
        self._state = "PLAYING"
        self._send(["set_property", "pause", False], wait=False)

    def pause(self) -> None:
        if self.running:
            self._awaiting_play = False
            self._state = "PAUSED_PLAYBACK"
            self._send(["set_property", "pause", True], wait=False)

    def stop(self) -> None:
        if self.running:
            self._awaiting_play = False
            self._state = "STOPPED"
            self._send(["stop"], wait=False)

    def set_volume(self, volume: int) -> None:
        if self.running:
            self._send(["set_property", "volume", max(0, min(100, volume))], wait=False)

    def seek(self, seconds: float, mode: str = "absolute") -> None:
        if self.running:
            self._send(["seek", seconds, mode], wait=False)

    def snapshot(self) -> dict[str, Any]:
        return {
            "running": self.running,
            "state": self._state,
            "position": self._position,
            "duration": self._duration,
            "path": self._path,
            "pid": self._process.pid if self._process else None,
        }

    def _log(self, message: str) -> None:
        try:
            self.on_log(message)
        except Exception:
            pass

    def _observe_on_event_pipe(self, name: str) -> None:
        pipe = self._event_pipe
        if pipe is None:
            return
        with self._write_lock:
            self._request_id += 1
            payload = {"command": ["observe_property", self._request_id, name]}
            pipe.write((json.dumps(payload) + "\n").encode("utf-8"))
            pipe.flush()

    def _send(self, command: list[Any], wait: bool = True) -> dict[str, Any] | None:
        if not self.running:
            return None
        with self._write_lock:
            self._request_id += 1
            request_id = self._request_id
            payload = {"command": command, "request_id": request_id}
            pipe = None
            try:
                pipe = self._connect_pipe(timeout=3.0)
                pipe.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
                pipe.flush()
            except (OSError, ValueError) as error:
                self._log(f"mpv IPC write failed: {error}")
                return None
            if not wait:
                try:
                    pipe.close()
                except OSError:
                    pass
                return None
            result: dict[str, Any] = {}
            deadline = time.monotonic() + 2.0
            try:
                while time.monotonic() < deadline:
                    line = pipe.readline()
                    if not line:
                        break
                    try:
                        message = json.loads(line.decode("utf-8", errors="replace"))
                    except json.JSONDecodeError:
                        continue
                    if message.get("request_id") == request_id:
                        result.update(message)
                        break
            finally:
                try:
                    pipe.close()
                except OSError:
                    pass
            return result

    def _reader_loop(self) -> None:
        pipe = self._event_pipe
        if pipe is None:
            return
        try:
            while not self._stop.is_set():
                line = pipe.readline()
                if not line:
                    break
                try:
                    message = json.loads(line.decode("utf-8", errors="replace"))
                except json.JSONDecodeError:
                    continue
                if message.get("event"):
                    self._handle_event(message)
        except (OSError, ValueError) as error:
            if not self._stop.is_set():
                self._log(f"mpv IPC read stopped: {error}")

    def _handle_event(self, message: dict[str, Any]) -> None:
        event = message.get("event")
        if event == "end-file":
            self._state = "STOPPED"
            self._emit_state()
            return
        if event != "property-change":
            return
        name = message.get("name")
        value = message.get("data")
        if name == "time-pos" and isinstance(value, (int, float)):
            self._position = float(value)
            if self.on_position_change:
                self.on_position_change(self._position, None)
        elif name == "duration" and isinstance(value, (int, float)):
            self._duration = float(value)
            if self.on_position_change:
                self.on_position_change(None, self._duration)
        elif name == "path" and isinstance(value, str):
            self._path = value
        elif name == "pause" and isinstance(value, bool):
            if self._state != "STOPPED":
                self._state = "PAUSED_PLAYBACK" if value else "PLAYING"
            if not self._awaiting_play:
                self._emit_state()

    def _emit_state(self) -> None:
        if self.on_state_change:
            try:
                self.on_state_change(self._state)
            except Exception:
                pass

    def _connect_pipe(self, timeout: float) -> Any:
        if os.name != "nt":
            raise MpvUnavailable("the bundled mpv controller currently requires Windows")
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        create_file = kernel32.CreateFileW
        create_file.argtypes = [
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.LPVOID,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.HANDLE,
        ]
        create_file.restype = wintypes.HANDLE
        path = self._pipe_name
        if not path.lower().startswith("\\\\.\\pipe\\"):
            path = "\\\\.\\pipe\\" + path
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            handle = create_file(path, 0xC0000000, 0, None, 3, 0, None)
            invalid = ctypes.c_void_p(-1).value
            if handle != invalid:
                self._pipe_handle = int(handle)
                fd = msvcrt.open_osfhandle(int(handle), os.O_RDWR | os.O_BINARY)
                return os.fdopen(fd, "r+b", buffering=0)
            error = ctypes.get_last_error()
            if error not in (2, 5, 231):
                raise OSError(error, f"CreateFileW({path}) failed")
            time.sleep(0.05)
        raise TimeoutError(f"timed out connecting to {path}")


if __name__ == "__main__":
    print("This module is loaded by mock_dlna_renderer.py", file=sys.stderr)
