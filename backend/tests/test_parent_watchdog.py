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


def _start_server_with_parent(tmp_path):
    """Start a stand-in parent and the real server watching it; wait until healthy."""
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
    deadline = time.monotonic() + 120
    while not _healthy(port):
        if server.poll() is not None or time.monotonic() > deadline:
            _kill(server, parent)
            raise AssertionError(f"server never became healthy (exit code {server.returncode})")
        time.sleep(0.5)
    return parent, server, port


def _kill(*procs):
    for proc in procs:
        if proc.poll() is None:
            proc.kill()
            proc.wait()


def test_server_exits_when_parent_dies(tmp_path):
    parent, server, _port = _start_server_with_parent(tmp_path)
    try:
        log = tmp_path / "logs" / "watchdog.log"
        assert log.exists() and "Parent watchdog started" in log.read_text(), (
            "parent watchdog never started"
        )

        parent.kill()
        parent.wait()
        # The watchdog polls every 2 s and waits 1 s for a keep-running request.
        server.wait(timeout=20)
    finally:
        _kill(server, parent)


def test_watchdog_disable_keeps_the_server_running(tmp_path):
    """POST /watchdog/disable is what "keep server running after close" sends."""
    parent, server, port = _start_server_with_parent(tmp_path)
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/watchdog/disable", method="POST")
        urllib.request.urlopen(request, timeout=5).close()
        parent.kill()
        parent.wait()
        # Twice the watchdog's 2 s poll plus its 1 s grace period.
        time.sleep(6)
        assert server.poll() is None, "server exited although keep-running was requested"
        assert _healthy(port)
    finally:
        _kill(server, parent)
