//! Tie the server sidecar's lifetime to the app's on Windows.
//!
//! The Windows server is a PyInstaller onefile build: the process the app
//! spawns (and knows the PID of) is the bootloader, which unpacks the
//! bundle to `%TEMP%\_MEI…`, runs the real server as its child, waits for
//! it, then deletes the unpacked folder. Quitting only reached the child —
//! `/shutdown` and the parent-PID watchdog both run inside it — and nothing
//! ever ended the bootloader, so one that did not exit by itself stayed
//! behind as an orphan `voicebox-server.exe`. (Unix kills the process group
//! instead.)
//!
//! The bootloader goes into a job object with `KILL_ON_JOB_CLOSE`: when the
//! app's handle to the job closes — at exit, crash included — Windows ends
//! every process still in it. On a normal quit the app first gives the
//! bootloader a few seconds to finish on its own, so it can remove its
//! unpacked folder. "Keep server running" lifts the limit before exiting.

use std::sync::OnceLock;
use std::time::Duration;

use windows::core::PCWSTR;
use windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
use windows::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT,
    JOB_OBJECT_LIMIT_BREAKAWAY_OK, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
use windows::Win32::System::Threading::{
    OpenProcess, WaitForSingleObject, PROCESS_SET_QUOTA, PROCESS_SYNCHRONIZE, PROCESS_TERMINATE,
};

/// The job handle. Never closed by hand: the OS closes it when the app
/// process ends, which is what ends the processes in it.
struct Job(HANDLE);
// A job handle is a plain kernel handle, usable from any thread.
unsafe impl Send for Job {}
unsafe impl Sync for Job {}

static JOB: OnceLock<Option<Job>> = OnceLock::new();

fn job() -> Option<HANDLE> {
    JOB.get_or_init(|| {
        let handle = match unsafe { CreateJobObjectW(None, PCWSTR::null()) } {
            Ok(handle) => handle,
            Err(e) => {
                eprintln!("server job: CreateJobObjectW failed: {e}");
                return None;
            }
        };
        if let Err(e) = set_limits(handle, true) {
            eprintln!("server job: SetInformationJobObject failed: {e}");
            return None;
        }
        Some(Job(handle))
    })
    .as_ref()
    .map(|job| job.0)
}

/// `BREAKAWAY_OK` so a process the server starts with
/// `CREATE_BREAKAWAY_FROM_JOB` still starts (it then lives outside the job).
fn set_limits(job: HANDLE, kill_on_close: bool) -> windows::core::Result<()> {
    let mut info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
    let mut flags = JOB_OBJECT_LIMIT_BREAKAWAY_OK;
    if kill_on_close {
        flags |= JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    }
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT(flags.0);
    unsafe {
        SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const std::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        )
    }
}

/// Put a just-spawned server process in the job. Done right after the
/// spawn, while the bootloader is still unpacking, so the server it starts
/// afterwards is in the job too.
pub fn adopt(pid: u32) {
    let Some(job) = job() else { return };
    unsafe {
        match OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, pid) {
            Ok(process) => {
                if let Err(e) = AssignProcessToJobObject(job, process) {
                    eprintln!("server job: could not assign PID {pid}: {e}");
                }
                let _ = CloseHandle(process);
            }
            Err(e) => eprintln!("server job: could not open PID {pid}: {e}"),
        }
    }
}

/// "Keep server running": let the server outlive the app.
pub fn release() {
    if let Some(Some(job)) = JOB.get() {
        if let Err(e) = set_limits(job.0, false) {
            eprintln!("server job: could not lift kill-on-close: {e}");
        }
    }
}

/// Wait up to `timeout` for a process to exit. True when it has (or is
/// already gone).
pub fn wait_for_exit(pid: u32, timeout: Duration) -> bool {
    unsafe {
        let Ok(process) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) else {
            return true;
        };
        let exited = WaitForSingleObject(process, timeout.as_millis() as u32) == WAIT_OBJECT_0;
        let _ = CloseHandle(process);
        exited
    }
}
