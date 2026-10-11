# nashclip production-readiness audit

**Date:** 2026-10-01  
**Scope:** Java Swing, JavaFX, native Swift/AppKit/SwiftUI, native shortcut helpers, shared storage, build scripts, and release packaging.

## Verdict

See the [2026-10-02 release recheck](release-verdict.md) for fresh test/build results and the current shipping decision.

**Do not treat this build as production approved yet.** This audit fixed concrete lifecycle, resource, storage, and usability defects. Automated checks support those fixes. They do not establish that the complete GUI is leak-free or that automatic paste is safe in every application.

Window targeting and automatic paste are working in the user's testing. The earlier Java PID-only targeting defect has been fixed; it is not an open, confirmed wrong-window defect. Remaining release work concerns UI-thread blocking, the documented plaintext-retention limitation, and release/runtime validation. Broader paste scenarios still need recorded acceptance results.

## Changes made during this audit

| Area | Defect or risk found | Change |
| --- | --- | --- |
| Window controls, all versions | Borderless drawers had no visible close/minimize controls. | Added **− Hide to edge** and **× Quit** with descriptions. Hide keeps capture running; Quit stops the app. The menu bar Quit action remains available. This is drawer hiding, not minimization to the Dock. |
| Swing shutdown | Clipboard polling had no stop operation; normal tray quit bypassed explicit drawer/timer disposal. | Added watcher stop, idempotent shutdown, paste interruption, helper cleanup, tray removal, and drawer/window disposal. |
| Swift repeating timer | Calling `start()` twice replaced the timer reference while the first timer stayed registered. Weak captures prevented model retention but did not unregister the orphan timer. | Made start idempotent; stop and deinit invalidate timers. Queued checks ignore a stopped model. |
| Swift native callbacks | Repeated starts could overwrite Carbon event-handler and workspace-observer tokens. | Guarded repeated starts, removed registrations on stop, cleared callbacks, and ignored stale queued work. |
| Swift delayed paste | Delayed work could still send keys after the service stopped. | Added cancellable work with generation checks and cleared retained target windows during stop. |
| Java subprocesses | Interrupted `osascript` waits could leave children alive; output was read only after waiting and streams were not consistently closed. | Added `CommandRunner`: five-second paste-command timeouts, concurrent output draining, a 4 KiB retained-output cap, tracked child termination, and stream cleanup in `finally`. |
| Java paste workers | Swing could launch overlapping non-daemon paste workers; hover/focus lookups blocked UI events. | Added a single-flight guard, tracked daemon workers, shutdown cancellation, and background target capture. Removed per-tile hover subprocess calls. |
| Synthetic key state | An exception after pressing Command could leave a modifier pressed. | Java releases V and Command in nested `finally` blocks; focus is checked after the activation delay. A later pass added native window verification, described below. |
| Shared files | Running multiple versions could overwrite history/settings independently. | Added one POSIX record lock per data directory, interoperable between Java and Swift. Second instances fail startup. The lock file remains; the OS releases the lock when the process exits. Older builds do not honor this lock. |
| Retained history | An arbitrary positive history count and arbitrary text length allowed unbounded retained text and eager UI growth. | Added count, per-clip, total-text, file-read, and preview budgets described below. |
| Swift history encoding | Iterating grapheme clusters failed to escape CRLF as separate characters; parsing all Unicode newline characters split valid clip content. | Escape Unicode scalars and split properties records only at CR/LF. Cross-language tests cover CRLF, emoji, U+2028, U+2029, tabs, backslashes, punctuation, and NUL. |
| Swift private writes | Final-file permissions were applied after writing. | Create the temporary file with mode 0600 before writing; rename it atomically within the private directory. |
| Save errors | Some settings/clear operations overwrote error status with success; Clear could remove in-memory history before a failed write. | Preserve failed-save status. Swift Clear writes the empty history before removing visible entries. Corrected Java settings recovery after the file becomes valid. |
| Clipboard failures | Swift and JavaFX ignored a failed clipboard write. | Do not attempt automatic paste when copying fails. |
| Capture privacy | There was no pause control. | Click **Live / Paused** in the header to pause capture for the session. Resume skips content copied during the pause. Restart resumes normal capture. |
| Local Swift package | `codesign --verify --deep --strict` failed: the executable's linker signature did not seal the app resources/Info.plist. | The build now applies an ad hoc bundle signature and verifies it. This is local development signing, not Developer ID signing or notarization. |

### Follow-up scrutiny and fixes

| Area | Finding | Fix |
| --- | --- | --- |
| Java paste destination | PID-only verification accepted any window in the destination app. | Added `NativeFocus` and a JNI/AppKit bridge that retains the captured AX window, freezes it for one Use operation, restores it, and compares the focused window before sending keys. Missing permission, missing bridge, invalid window, or failed verification leaves the text copied for manual paste. Native AX references have explicit ownership and disposal. |
| Swift paste destination | Workspace events could replace the destination while the drawer was closing. | Freeze the destination before closing and pass that snapshot through the delayed paste operation. Require an identifiable original window. |
| Clipboard changes during paste | A new copy during the restore delay could change what gets pasted. | Verify expected text before restoration and immediately before sending keys in all variants. Swift also checks the pasteboard change counter. These checks cannot make OS focus and clipboard operations atomic. |
| Drawer state transitions | Resizing during JavaFX/Swift close animation could discard its completion and leave Use permanently busy. | Finish an accepted close exactly once before resizing. Explicitly refuse close while dragging/resizing and release the caller's Use guard. Ignore pending completions after disposal. Header controls no longer start JavaFX header dragging. |
| Swing disposal | Late calls could recreate the edge handle after disposal; hiding during a resize could leave the drawer stuck. | Guard disposed entry points, clear drag/resize state on immediate hide and resize, and release the resize callback during close. |
| Busy clipboard reads | Java treated some failed reads as empty clipboard state, causing duplicate clips or repeated size notices. Resume after a failed baseline read could capture paused content. | Keep the previous observation on a failed read and seed the first successful read after resume. Swift retries unavailable advertised text and rejects a read if the change counter moves mid-read. |
| Cross-version Unicode pins | Swift's canonical-equivalence comparison could merge distinct NFC/NFD text that Java stores separately. Nonbreaking-space acceptance differed too. | Compare exact UTF-16 text for clip identity and align whitespace acceptance. Tests exchange separately pinned NFC/NFD strings between Java and Swift. |
| Overlay lifecycle | JavaFX's root-width listener and binding retained a closed size notice. | Remove both listeners, unbind width, and detach the overlay. Closed Java notices ignore later events. |
| Shutdown/native resources | Service shutdown needed explicit JNI ownership cleanup and helper-stream cleanup. | Close native target references, remove normal-exit shutdown hooks, and close helper streams in `finally`. Swift removes its workspace observer and cancels paste work during deinit. |

Java paste no longer launches periodic `osascript` processes. Its background focus monitor calls the JNI bridge every 1.5 seconds; hotkey and Use paths also capture the destination. `CommandRunner` remains as an unused utility with its existing regression tests. Its descriptor results do **not** measure JNI or UI allocations.

### Memory and file budgets

The three variants use the same policy:

- `historyLimit`: **1–500** ordinary clips, default **40**, plus up to **five** persisted pinned clips. Pinned clips reserve their text budget before ordinary clips and are never evicted automatically.
- Individual clip: at most **65,536 UTF-16 code units**. New oversized clips are skipped, not truncated for copying. A dismissible red overlay reports the rejected size; only notice metadata is kept. `sizeNoticeSeconds` controls dismissal (2–5 seconds, default 4; 0 requires dismissal). Hidden drawers defer the notice until opened.
- Retained history text: at most **1,048,576 UTF-16 code units**. Oldest unpinned entries are removed when this budget or the ordinary-history count limit is reached.
- Tile preview: at most **1,000 UTF-16 code units**, followed by an explanatory marker. **Use** copies the complete accepted clip. Preview boundaries preserve surrogate pairs.
- History file read: at most **16 MiB**; configuration/position/size reads: at most **64 KiB**.
- An existing saved clip above the per-clip limit causes loading to fail and automatic history saving to pause, preserving the source file. Fix or archive that file before retrying.

These are retention limits, **not a process-RSS guarantee**. Strings, layout nodes, graphics buffers, parser structures, and runtime caches consume additional memory. Standard clipboard APIs can materialize a large clipboard value before its size is checked. The count/text budget does not bound that transient allocation.

Invalid settings are preserved. A history limit above 500 now requires a manual correction before normal persistence resumes. Reducing a valid limit or exceeding the total-text budget evicts older clips; keep a backup if they matter.

## Remaining findings

P1 means a release blocker for the intended reliable everyday app. P2 means a material limitation or follow-up. None of these entries claims a confirmed exploitable vulnerability without evidence.

### Validation gap — Broader paste acceptance scenarios

All versions now retain an AX window and compare it with the focused window before sending keys. Java's former PID-only check is fixed. Swift freezes its target before the close animation; all versions check clipboard content after the restoration delay.

**User confirmation, 2026-10-02:** window targeting and automatic paste are working in the user's current testing. This supersedes any earlier wording that described wrong-window pasting as an unresolved observed defect. The specific OS/application combinations and edge-case results have not been recorded, so this confirmation does not mark every acceptance scenario as completed.

These checks cannot verify the exact editable field or that the destination accepted the paste. Focus and clipboard state can still change between verification and event delivery. Periodic Java capture can also retain an older window if the panel gains focus before the next capture. The native bridge lifetime tests intentionally use empty targets and never activate another app or send keys; they do not prove multiwindow restoration.

**Required validation:** multiple windows of the same app, Spaces, fullscreen, app exit/relaunch, permission denial/revocation, and focus or clipboard changes during the delay. Confirm uncertain destinations fail closed and manual Command+V remains available. Use disposable text for this acceptance run.

### P1 — Clipboard, disk, and layout work can stall the UI

**Evidence:** Java watchers poll on the Swing/JavaFX UI thread. `NashClip.persistHistory`, `NashClipApp.persistHistory`, and Swift `ClipboardModel.saveHistory` perform synchronous serialization and disk writes from UI callbacks. History views create/layout all retained tiles. Swift AX calls also run on the main actor; messaging timeouts reduce some waits but do not eliminate all blocking.

The new caps reduce the worst retained payload. They do not guarantee responsive scrolling or animation during slow storage, large clipboard reads, or accessibility calls.

**Required work:** serialize persistence through one bounded background writer with immutable snapshots, coalesce obsolete writes, surface errors, and flush on shutdown. Move safe platform calls away from the UI thread; respect toolkit thread requirements. Profile before choosing tile virtualization or layout caching. Test disk-full/read-only paths and rapid resize/copy sequences.

### P1 — Complete GUI/native leak profiling remains outstanding

**Evidence available:** source lifecycle review, bounded subprocess stress, descriptor counts, storage stress, isolated Swift timer tests, JNI context lifecycle checks, and desktop drawer lifecycle tests. JavaFX test windows are removed on close; Swift test controllers are released. Swing tests check timer shutdown and native window disposal. These do not measure retained Swing/JavaFX scene graphs, AppKit windows, Core Animation surfaces, native AX objects, or graphics resources under sustained real use.

**Required work:** perform the release soak below using Instruments and JVM profiling on the target machines. Compare post-GC live objects and retained bytes, not only RSS; allocators and runtime caches can keep RSS high without leaking objects.

### P1 — Runtime/dependency patch and release gates are incomplete

The tested local runtime is **Homebrew OpenJDK 17.0.14**; the Maven project pins **JavaFX 21.0.10**. Newer maintenance releases exist. Oracle documents JDK 17.0.19, and Gluon documents JavaFX 21.0.11 and later 21.x release notes. This proves the checked versions are behind maintenance releases; it is not a CVE assessment of this app. See [Oracle's JDK release notes](https://www.oracle.com/java/technologies/javase/17-0-19-relnotes.html) and [Gluon's JavaFX 21 release notes](https://gluonhq.com/products/javafx/openjfx-21-release-notes/).

**Required work:** select supported patched runtimes, scan the shipped dependency set, and rerun compatibility tests. No dependency vulnerability scanner or full Maven packaging/release pipeline was run here. No runtime was installed or upgraded during this audit.

The Swift app builds for the current architecture, targets macOS 13, and was compiled here using Swift 6.4 and SDK 26.5. There is no Intel/macOS-13 runtime test evidence. Ad hoc signing does not replace Developer ID signing, hardened-runtime review, notarization, clean-machine installation, and stable Accessibility-permission testing. No signing credentials were used.

### P2 — Clipboard history remains sensitive plaintext

Private file permissions restrict access by other local users, but history is unencrypted and readable by software running as the same user. There is no excluded-app policy, secret-format filtering, expiration policy, or encrypted store. Pause is session-only. Hiding the drawer continues capturing. The legacy `~/.mcopier` migration backup, filesystem snapshots, and backups can retain clips after deletion from the current history.

**Required work:** decide the retention/privacy contract, make exclusions and pause discoverable, and test it. Deletion is not secure erasure and does not clear the system clipboard.

### P2 — Screen changes, small windows, and accessibility need manual QA

Saved-position and drag code clamp windows to a selected screen, but there is no dedicated display-disconnect/reconfiguration observer that relocates an already hidden handle. Test unplugging a display, negative display coordinates, scaling, Stage Manager, fullscreen, and Spaces. Header controls may crowd at minimum width and long/localized text. Swing deliberately uses non-focusable controls/windows to preserve the paste destination; keyboard navigation and VoiceOver need design work.

**Required work:** add display-change recovery, exercise minimum/maximum dimensions on actual screens, and validate all controls with keyboard and assistive technology. Native window minimize semantics differ from this drawer's Hide action.

### P2 — Feature parity and maintenance gaps

Swing still does not persist drawer position. Only Swift has a Clear History command. Search, login startup, and image history remain absent. Individual clip pinning was added after the audit: five persisted slots, protected from automatic eviction. Clipboard polling can miss intermediate rapid copies and repeated identical values. The header pin holds the drawer open; tile pins retain individual clips. Java's periodic native focus capture adds background wakeups; evaluate activation/window notifications after profiling.

Regression tests cover resize during close, close refusal during resize, outside-click dismissal, click-inside latching, and disposal with a pending completion. Real pointer dragging, modal dialogs, screen changes, and every possible event interleaving still need manual QA.

Storage uses same-directory atomic replacement, but does not fsync both file and directory for a power-loss durability guarantee. Symlink checks are defensive and are not protection against a malicious process running as the same user that races filesystem operations. The custom Java-properties parser is an app-format subset, not a complete implementation of every hand-edited Java properties syntax.

## Automated verification

For the expanded per-version suites, named results, and manual acceptance procedures, see the [testing guide](testing.md). Run `python3 scripts/test.py` to exercise all three versions and write JSON/JUnit reports. The audit commands below remain useful for the original focused regressions.

Run from the repository root on macOS:

```sh
python3 scripts/audit-checks.py --lifecycle
python3 scripts/gui-checks.py
sh java-swing/build.sh
(cd java-fx && mvn -o -q -s maven-settings.xml -Dmaven.repo.local=target/m2 compile)
sh nashclip-swift/build.sh
```

The scripts need JDK 17+, Swift/Xcode Command Line Tools, and Python 3. The desktop suite also needs cached JavaFX platform jars and a logged-in desktop session. It creates disposable test windows (normally transparent), uses private clipboards, and never sends paste keystrokes. It uses temporary data directories. The optional lifecycle check uses a private named pasteboard; it never reads or changes the system clipboard. A restricted sandbox may block process enumeration or pasteboard services. Run locally with normal desktop-session access to obtain those checks. Maven's offline command requires dependencies to have been cached already.

Recorded results:

- Swing and JavaFX builds passed; Swift build passed and its local bundle passed strict signature verification.
- Both Java variants passed storage/size round trips, malformed-settings/history rejection, Unicode preservation, private-file permissions, preview boundaries, count/text budgets, and temporary-file cleanup.
- In each Java variant, **200 subprocess commands** left the open-descriptor count at **7 before / 7 after**. An output flood timed out without pipe deadlock. Twenty alternating interruption/close cycles completed with no surviving child processes. Calls after close were rejected.
- Swift passed storage/size round trips, Unicode preservation, bounds, malformed input, permissions, and temporary-file cleanup.
- Java → Swift and Swift → both Java variants preserved Unicode history and pinned text, including distinct NFC/NFD spellings, nonbreaking spaces, and pin order.
- All **nine holder/contender combinations** of Swing/JavaFX/Swift rejected a second process while locked and allowed acquisition after release.
- Final expanded suite passed oversized-history-file and oversized-existing-clip rejection checks. Existing rejected clip data remained unchanged.
- Swift passed **200 start/start/stop/stop/restart/deinit cycles**: repeated start reused the same timer, stop/deinit invalidated timers, and released models were no longer retained. A private-pasteboard test verified capture, pause, and that resume skipped text copied during the pause.

- Each Java bridge passed **300 create/freeze/release/close cycles** with `-Xcheck:jni`, including invalid and disposed targets. No keys were sent. These empty-target checks do not exercise AX reference retention for real external windows.
- Swing's private clipboard test passed busy-read retry, pause/resume after a failed baseline read, oversize-notice deduplication, own-copy suppression, and stop behavior.
- Swing passed **12 drawer lifecycle cycles** covering outside/inside clicks, immediate hide during resize, and late calls after close. All four drawer timers stopped and no test window remained displayable. Closed size notices ignored further updates.
- JavaFX and Swift each passed **12 drawer lifecycle cycles**, including resize during hide, busy-close refusal, outside/inside click behavior, and shutdown with a pending completion. JavaFX had zero open windows after each cycle and its size overlay detached. Swift controllers were released after close.

The storage/process Java tests use a 128 MiB Java heap. They do not run the complete app under that limit. No production clipboard content or existing user history was used as test data.

## Feature coverage and limits

| Feature | Evidence in this pass |
| --- | --- |
| Capture, pause, oversized clips | Deterministic private Swing clipboard and Swift private-pasteboard checks; JavaFX watcher source review and full compile. |
| History, five pins, budgets, persistence, configuration | Cross-language fixtures, malformed input, bounds, and locking tests. Pin/unpin/delete save ordering reviewed. Real button clicks and disk-full UI behavior remain manual checks. |
| Drawer animation, dismissal, resize, shutdown | Desktop regression harnesses; no real mouse automation or sustained animation profiling. |
| Use / automatic paste | User reports working window targeting and paste. Automated checks cover destination/cancellation decisions and native context lifetime; they do not deliver keys to external apps. The broader acceptance matrix remains unrecorded. |
| Tray, shortcut, themes, dialogs, positions | Source review and compilation. Human interaction, display changes, shortcut conflicts, and assistive technology remain manual checks. |
| Packaging | Swift local bundle builds and passes strict ad hoc signature verification. Distribution signing/notarization and clean-machine installs remain untested. |

## Release soak and acceptance checklist

1. Use disposable test clips and a backed-up test data directory. Warm up each app, then establish live-object, thread, descriptor, child-process, and window-count baselines.
2. Over at least one hour, repeat open/hide, both-side docking, pin/unpin, theme changes, resize, modal Save/Cancel, copy, delete, and Use. Include the 500-entry limit and the full text budget. Verify steady-state resources plateau after warmup and collection; investigate any monotonic retained-object growth.
3. For Java, inspect heap histograms/dumps, thread dumps, and child processes. For Swift, use Instruments Allocations/Leaks and Memory Graph to inspect `ClipboardModel`, drawer controllers, `NSPanel`, timers, observers, AX references, and closures. Keep profiles in a restricted location because heap dumps can contain clipboard text.
4. Quit while hovering, animating, resizing, writing, or waiting to paste. Verify both windows and tray item disappear, no helper remains, the hotkey becomes available, no keys are posted after shutdown, and a new instance can acquire the lock.
5. Test corrupted/oversized settings and history, an unwritable/full disk, symlinked files, interrupted writes, and attempts to run two versions. Original unreadable data must remain intact; failures must remain visible and recovery must work.
6. Exercise paste with two windows of the same app, multiple Spaces/displays, app termination, denied/revoked Accessibility permission, and a clipboard/focus change during the restore delay. The app must refuse an uncertain destination.
7. Verify the new Hide/Quit/Pause controls visually at minimum width, test keyboard and VoiceOver access, and verify behavior on clean supported macOS installations for each shipped architecture/runtime.
8. Approve distribution only after the P1 findings have fixes and recorded results. Retain the test logs and profiler evidence with the release.
