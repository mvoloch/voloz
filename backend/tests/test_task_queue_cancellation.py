import asyncio

import pytest

from backend.services import task_queue


@pytest.mark.asyncio
async def test_cancel_queued_generation_skips_execution():
    task_queue.init_queue(force=True)

    running_started = asyncio.Event()
    release_running = asyncio.Event()
    queued_ran = asyncio.Event()

    async def running_job():
        running_started.set()
        await release_running.wait()

    async def queued_job():
        queued_ran.set()

    task_queue.enqueue_generation("gen-running", running_job())
    await asyncio.wait_for(running_started.wait(), timeout=1)

    task_queue.enqueue_generation("gen-queued", queued_job())
    assert task_queue.cancel_generation("gen-queued") == "queued"

    release_running.set()
    await asyncio.sleep(0.1)

    assert not queued_ran.is_set()


@pytest.mark.asyncio
async def test_cancel_running_generation_cancels_task():
    task_queue.init_queue(force=True)

    running_started = asyncio.Event()
    running_cancelled = asyncio.Event()

    async def running_job():
        running_started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            running_cancelled.set()
            raise

    task_queue.enqueue_generation("gen-running", running_job())
    await asyncio.wait_for(running_started.wait(), timeout=1)

    assert task_queue.cancel_generation("gen-running") == "running"
    await asyncio.wait_for(running_cancelled.wait(), timeout=1)


@pytest.mark.asyncio
async def test_worker_failure_keeps_the_cause_in_the_error(monkeypatch):
    """A job that dies without writing its own status used to leave only
    "Worker exited without writing terminal status"; the cause must show."""
    task_queue.init_queue(force=True)
    recorded = {}

    async def fake_force_fail(generation_id, error):
        recorded[generation_id] = error

    monkeypatch.setattr(task_queue, "_force_fail_if_active", fake_force_fail)

    async def failing_job():
        raise RuntimeError("database is locked")

    task_queue.enqueue_generation("gen-broken", failing_job())
    for _ in range(50):
        if "gen-broken" in recorded:
            break
        await asyncio.sleep(0.02)

    assert "Worker exited without writing terminal status" in recorded["gen-broken"]
    assert "database is locked" in recorded["gen-broken"]
