"""Run the checked-out Macast renderer with verbose DLNA tracing."""

from __future__ import annotations

import logging
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parent / "Macast-reference"
MPV = Path(__file__).resolve().parent / "mpv" / "mpv.exe"
sys.path.insert(0, str(ROOT))

from macast import Setting  # noqa: E402
from macast.macast import cli  # noqa: E402
import macast.server as _server  # noqa: E402


Setting.base_path = str(ROOT)
Setting.mpv_default_path = str(MPV)
# Keep the trace run independent from a possibly running installed Macast
# instance whose log file may be locked by another process.
TRACE_DIR = ROOT.parent / "macast-trace-data"
TRACE_DIR.mkdir(parents=True, exist_ok=True)
_server.SETTING_DIR = str(TRACE_DIR)
TRACE_LOG = TRACE_DIR / "trace.log"
trace_handler = logging.FileHandler(TRACE_LOG, mode="w", encoding="utf-8")
trace_handler.setFormatter(logging.Formatter(
    "%(asctime)s %(levelname)s %(name)s %(message)s"))
logging.getLogger().setLevel(logging.DEBUG)
logging.getLogger().addHandler(trace_handler)
Setting.set_temp_friendly_name("Macast Trace Renderer")
logging.basicConfig(level=logging.DEBUG,
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")

if __name__ == "__main__":
    cli()
