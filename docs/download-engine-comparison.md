# Download engine: Android DownloadManager vs. our own transfer inside WorkManager

Status: comparison for the DownloadWorker phase. The shell (scheduling, policy gate, pause and resume
orchestration, persistence, events) stays the same either way; only the `DownloadEngine` implementation changes.

## The two options

|                     | A. Android `DownloadManager`                                   | B. Own HTTP transfer in the WorkManager worker                           |
|---------------------|----------------------------------------------------------------|--------------------------------------------------------------------------|
| Who moves the bytes | System `DownloadProvider` process                              | Our `download_run` worker, on `Dispatchers.IO`                           |
| Where it runs       | Outside our process                                            | Inside our process, as a `dataSync` foreground service                   |
| What we write       | Thin `DownloadEngine` that enqueues, pauses, resumes and polls | Full transfer: HTTP client, `.part` file, Range resume, integrity, retry |

## Comparison

| Concern                                         | A. DownloadManager                                                                                                                                                                                            | B. Own transfer                                                                              |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| HTTP transfer, Range resume, retry              | Built in, system-tested                                                                                                                                                                                       | We write and test it (requirements section 12)                                               |
| Survives our process death                      | Yes, the transfer keeps going                                                                                                                                                                                 | Transfer stops; WorkManager re-runs the worker and we resume from the `.part` file           |
| Survives reboot                                 | Yes, the provider resumes                                                                                                                                                                                     | WorkManager restores the work; we resume from the `.part` file                               |
| Foreground service needed                       | No                                                                                                                                                                                                            | Yes, `dataSync` type                                                                         |
| Android 15 `dataSync` six-hour daily limit      | Not applicable                                                                                                                                                                                                | Applies; the system-stop path handles the timeout and a 500 MB file on slow Wi-Fi can hit it |
| Pause when the car starts moving                | **Public API has no pause.** `remove()` deletes the partial file. The provider's `control` column pauses and resumes, but it is hidden and needs `ACCESS_DOWNLOAD_MANAGER_ADVANCED` (signature or privileged) | Immediate: cancel the coroutine, keep the `.part` file, resume with `Range`                  |
| Battery rule                                    | Not aware of it; our shell must pause and resume                                                                                                                                                              | Same; our shell pauses and resumes                                                           |
| Network rule                                    | `setAllowedNetworkTypes` and `setAllowedOverMetered(false)` enforce it in the provider as well as in our gate                                                                                                 | Enforced only by our gate and mid-run watcher                                                |
| Progress source                                 | Poll the status cursor (about once a second)                                                                                                                                                                  | Direct from the stream                                                                       |
| Progress notification                           | The provider shows its own, or we hide it and show ours                                                                                                                                                       | Ours only                                                                                    |
| Integrity check, checksum, file-type sanity     | We do it after completion                                                                                                                                                                                     | We do it after completion                                                                    |
| Server quirks (no Range support, changing ETag) | Provider behaviour is fixed; a server without Range restarts from zero                                                                                                                                        | We decide: soft pause, restart, `If-Range`                                                   |
| Target file location                            | Provider-chosen or app external files dir; readable by the installer                                                                                                                                          | Our app files dir                                                                            |
| Availability on the image                       | `com.android.providers.downloads` is optional in automotive builds                                                                                                                                            | Always available                                                                             |
| Code we own and test                            | Small                                                                                                                                                                                                         | Large; this is the deferred phase in the requirements                                        |
| Debuggability                                   | Provider logs and cursor state, less visibility                                                                                                                                                               | Full visibility in our logs                                                                  |

## What decides it

1. **Is the downloads provider on the target image?** Check with `adb shell pm list packages | grep providers.downloads`. If it is missing, option A is out.
2. **Can a privileged app pause and resume?** With `ACCESS_DOWNLOAD_MANAGER_ADVANCED` granted, update the download row's `control` column to `1` (paused) and back to `0`, and confirm the transfer stops and continues. Without a working pause, every drive would restart a 500 MB transfer from zero, which rules option A out.
3. **Does the server support Range?** Both options need it for resume. Option B can also soft-pause or restart on its own terms; option A cannot.

## Recommendation

- If both checks in 1 and 2 pass: **option A**. The system does the transfer, our process only decides when
  it may run, there is no foreground service and no six-hour limit. The hidden pause is the single
  undocumented dependency, so pin it with a probe in the test plan and re-verify on each platform upgrade
  (Android 16 is planned).
- Otherwise: **option B**, as laid out in requirements section 12.

Either way the shell is unchanged: `RunCoordinator` cancels the engine coroutine to pause, calls
`download()` again to resume, and `discard()` on user cancel. For option A those map to writing the pause
control, clearing it, and `remove()`.

## Why not a plain thread or a persistent service instead of WorkManager

The transfer already runs on a background coroutine inside the worker. Replacing WorkManager would remove the
persisted daily trigger, the restore after reboot and the automatic re-run after a process kill, all of
which would then have to be rebuilt on `JobScheduler` and `AlarmManager`. Marking the app
`android:persistent="true"` keeps the process alive permanently, which avoids the kill cases but still does
not provide the schedule and costs memory on the head unit. WorkManager stays in both options.
