"""
The server the desktop app starts must exit when the app goes away.

server.py runs a parent-PID watchdog for this (--parent-pid). It used to be
registered with @app.on_event("startup"), which FastAPI ignores once the app
has a lifespan, so the watchdog never ran and the server outlived the app,
e.g. after an installer force-closed it during an update.
"""

import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SERVER = REPO_ROOT / "backend" / "server.py"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as resp:
            return json.load(resp).get("status") == "healthy"
    except OSError:
        return False


def test_server_exits_when_parent_dies(tmp_path):
    parent = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(600)"])
    port = _free_port()
    server = subprocess.Popen(
        [
            sys.executable,
            str(SERVER),
            "--port", str(port),
            "--data-dir", str(tmp_path),
            "--parent-pid", str(parent.pid),
        ],
        cwd=REPO_ROOT,
        env={**os.environ, "PYTHONPATH": str(REPO_ROOT)},
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 120
        while not _healthy(port):
            assert server.poll() is None, f"server exited early with code {server.returncode}"
            assert time.monotonic() < deadline, "server did not become healthy within 120 s"
            time.sleep(0.5)

        log = tmp_path / "logs" / "watchdog.log"
        assert log.exists() and "Parent watchdog started" in log.read_text(), (
            "parent watchdog never started"
        )

        parent.kill()
        parent.wait()
        # The watchdog polls every 2 s and waits 1 s for a keep-running request.
        server.wait(timeout=20)
    finally:
        for proc in (server, parent):
            if proc.poll() is None:
                proc.kill()
                proc.wait()
