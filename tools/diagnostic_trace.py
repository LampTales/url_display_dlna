"""Optional JSON-lines diagnostics shared by the renderer and mpv controller."""

from datetime import datetime, timezone
import json
from pathlib import Path
import threading
import time


class DiagnosticTrace:
    def __init__(self) -> None:
        self._file = None
        self._lock = threading.Lock()
        self._sequence = 0

    def open(self, path: str) -> None:
        self._file = Path(path).open("a", encoding="utf-8", buffering=1)

    def __call__(self, kind: str, **fields: object) -> None:
        if self._file is None:
            return
        with self._lock:
            self._sequence += 1
            record = {
                "sequence": self._sequence,
                "time": datetime.now(timezone.utc).isoformat(),
                "monotonic": time.monotonic(),
                "thread": threading.current_thread().name,
                "kind": kind,
                **fields,
            }
            self._file.write(json.dumps(record, ensure_ascii=False) + "\n")

    def close(self) -> None:
        with self._lock:
            if self._file is not None:
                self._file.close()
                self._file = None
