# Testing nashclip

Each implementation has its own runnable suite. The combined runner also checks shared data compatibility and single-instance locking between versions. Tests use the existing JDK and Swift compilers; no extra test-library download is required.

The suite combines named behavioral cases with the earlier grouped regression checks. Optional instrumentation measures production-source coverage and enforces thresholds. Native integration and acceptance checks appear as **skipped** in the reports until you perform the manual procedure below.

## Run the suites

GitHub CI is configured in [ci.yml](../.github/workflows/ci.yml) for Python tooling tests, macOS headless/core and cross-version tests, native builds, and unsigned Swift package previews on Apple Silicon and Intel. The workflow has not yet been run on GitHub. It does not run the desktop acceptance matrix or the full coverage gate. Local release validation remains required; see [RELEASING.md](../RELEASING.md).

Run the Python runner and packaging checks with:

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

From the repository root:

```sh
# One version, including desktop tests
sh java-swing/test.sh
sh java-fx/test.sh
sh nashclip-swift/test.sh

# All versions plus cross-version persistence and locking
python3 scripts/test.py

# Core tests without opening test windows or using pasteboard services
python3 scripts/test.py --headless

# One group while debugging; quote the wildcard
sh java-fx/test.sh --only 'FxClipboardTests'
sh java-swing/test.sh --only 'PasteServiceTests'
sh nashclip-swift/test.sh --only '*Drawer*'

# Choose an output directory or list test groups without running
python3 scripts/test.py --report-dir test-results/my-run
python3 scripts/test.py --list
```

Use a logged-in macOS desktop session for the full suite. Test windows are normally transparent and may briefly appear. Leave the test windows alone while a run is in progress. `--headless` records desktop/native checks as skipped; it still requires macOS and the compilers. The older `scripts/audit-checks.py` and `scripts/gui-checks.py` commands remain available.

### Requirements

- JDK 17 or later, with `java` and `javac` on `PATH`.
- Python 3.10 or later.
- Xcode Command Line Tools for `swiftc`, `xcrun`, and native Java helpers.
- The JavaFX platform jars matching `java-fx/pom.xml`, cached in `java-fx/target/m2`.

Prepare the JavaFX cache once if needed:

```sh
cd java-fx
mvn -q -s maven-settings.xml -Dmaven.repo.local=target/m2 compile
```

The test runner reads the JavaFX version from the POM and selects the platform jars for the host architecture. It fails clearly if they are missing. It does not install runtimes or download dependencies itself. `JAVA_HOME` selects the JDK headers for native helper builds.

Swift uses the project's SDK selection convention. Set `NASHCLIP_SDK` to an installed SDK path if your compiler needs a different SDK. The default first checks the installed 26.5 SDK used by this project, then asks `xcrun`.

### Isolation

Every run creates temporary data directories. Java runs with a temporary `user.home`; Swift models receive explicit temporary storage. Migration tests use a disposable home directory. JavaFX uses an injected in-memory clipboard, Swing uses a private Java clipboard, and Swift uses a private named pasteboard.

Paste tests inject OS effects and record whether the real orchestration requests delivery. They never send real Command+V events. Shortcut protocol tests run disposable fixture helpers, not the actual global shortcut registration. Native bridge checks allocate/dispose contexts and reject empty targets; they do not activate other apps. No suite changes Accessibility permissions or reads existing clipboard history.

Subprocesses have deadlines. A timeout kills the test's process group, including its disposable helper children. Temporary data is removed at the end; reports remain. Build checks compile application code. Java native checks rebuild the helpers inside their version directories.

## Read the results

The default output directory is `test-results/latest`. A later run replaces its summary and XML; choose `--report-dir` to retain a separate run.

| File | Purpose |
| --- | --- |
| `summary.json` | Every case, status, duration, failure detail, and log path. |
| `junit.xml` | JUnit-compatible results for CI/test-report viewers. |
| `*.log` | Compiler and test-program output. |

Exit code **0** means all selected automated checks passed. Exit code **1** means a compilation, prerequisite, infrastructure, or test failure. Manual checks and checks excluded by flags are reported as skipped. A successful automated run with manual skips is **not release approval**.

Named tests emit a `TEST` record after each case. An unexpected program crash or timeout adds a failed group result. Older harnesses, such as the storage/resource stress program, are reported as one group containing many assertions. Build steps are also counted in the summary; the total is not a count of individual assertions.

The JSON `by_kind` field separates tests, builds, fixture creation, and manual checks. Console test totals exclude builds and fixture creation. The runner rejects silent test exits, malformed result records, nonfinite durations, and reported failures even if a process exits with code 0. Its own regression suite runs with every unfiltered invocation; run it alone with `python3 tests/test_runner.py`.

## Measured coverage and quality gates

From the repository root:

```sh
# One-time download of pinned, checksum-verified JaCoCo tools; test-only dependencies
python3 scripts/setup-coverage.py

# Measure and enforce thresholds for all versions
python3 scripts/test.py --check-coverage --report-dir test-results/coverage-audit

# Measure one complete variant, without enforcing thresholds
sh java-fx/test.sh --coverage --report-dir test-results/fx-coverage

# Check whether assertions detect deliberately seeded regressions
python3 scripts/mutation-checks.py
```

Coverage runs use JaCoCo 0.8.15 for Java and the installed Swift compiler's LLVM coverage tools. Java reports line and branch coverage; Swift reports line and region coverage. A Swift region is not equivalent to a Java branch. JaCoCo also excludes exception handling from its branch counter. See [JaCoCo's counter definitions](https://www.jacoco.org/jacoco/trunk/doc/counters.html) and [LLVM's source coverage documentation](https://clang.llvm.org/docs/SourceBasedCodeCoverage.html).

Two scopes are always reported:

- **Overall:** every production `.java` or `.swift` source file, including unexecuted startup, UI wiring, and OS adapters. Test sources are excluded. Native Objective-C helper implementations are **not instrumented** by these reports.
- **Core:** capture, history retention, settings, persistence, migration, position/size storage, and locking. The exact file lists are in `scripts/coverage_tools.py` and copied into each result. This subset does not include the entire paste implementation or app startup.

The policy in `tests/coverage-policy.json` requires **90% core lines** in each version, **85% core Java branches**, and **85% core Swift regions**. It also sets whole-source regression floors of 62% Swing, 75% JavaFX, and 61% Swift. These lower floors make coverage loss visible; they are not release-readiness targets. Raise them as integration coverage improves. Do not lower them or remove files to make a failing check pass.

`--check-coverage` rejects filtered/headless runs and fails if a selected variant or required metric is missing. `--coverage` allows headless measurement but not `--only`; headless results include unexecuted desktop code and must not be compared to a full run as if they exercised the same behavior. Each run clears its coverage output before execution, preventing stale profiles from inflating results.

| Coverage output | Contents |
| --- | --- |
| `coverage/summary.json` | Exact covered/total counts, percentages, file lists, and scopes. |
| `coverage/swing/html/index.html`, `coverage/fx/html/index.html` | Java source highlighting and branch details. |
| `coverage/swift/html/index.html` | Swift source highlighting. |
| `coverage/*/jacoco.xml`, `coverage/swift/llvm.json` | Machine-readable instrumentation reports. |

The mutation check compiles unchanged controls first, then changes **temporary copies** of production files. It tests clip-length boundaries, the sixth-pin rejection, ordinary-history limits, wrong-window paste refusal, clipboard-change refusal, frozen-target release, and exact Unicode identity. A mutation counts as detected only when a named test fails with a nonzero process exit. Compilation errors, unexplained crashes, and timeouts fail the check; they do not count as successful detections. This is a targeted check of important assertions, not an exhaustive mutation score.

Coverage measures execution. It does not prove that an assertion is correct, native integration works, or memory cannot leak. Keep the manual gates below alongside these measurements. See [the test scrutiny report](test-scrutiny.md) for findings and the measured run.

## Automated feature coverage

| Functionality | Swing | JavaFX | Swift |
| --- | --- | --- | --- |
| Startup defaults and persisted settings | `SettingsPolicyTests` | `SettingsPolicyTests` | `StorageFeatureTests` |
| Invalid settings/history, budgets, permissions, symlinks, Unicode | `AuditChecks`, `SettingsPolicyTests`, `DataValidationTests` | Same shared Java cases | `StorageChecks`, `StorageFeatureTests`, `StorageValidationTests` |
| Legacy folder migration, current data preserved | `SettingsPolicyTests` | `SettingsPolicyTests` | `StorageFeatureTests` |
| Capture, duplicate suppression, blank text, startup baseline | `ClipboardWatcherChecks`, `SwingFeatureTests` | `FxClipboardTests`, `FxFeatureTests` | `ModelFeatureTests`, `SwiftLifecycleChecks` |
| Pause/resume and busy clipboard reads | Private Swing clipboard failures | Injected JavaFX clipboard failures | Private pasteboard pause/resume; delayed producer failures remain manual |
| Oversize rejection and notice metadata | Watcher and storage cases | Watcher and storage cases | Model and storage cases |
| Notice expiry, hidden deferral, manual dismissal | `SwingInteractionTests` | `FxInteractionTests` | `ViewDrawerFeatureTests` |
| Five-pin cap, unpin, recopy, delete, restart | Real tile actions in `SwingFeatureTests` | Real tile actions in `FxFeatureTests` | Model actions in `ModelFeatureTests` |
| Failed pin/delete save preserves visible state | Real tile actions with a blocked destination | Same | Model actions with a blocked destination |
| Clear History keeps pins and clipboard | Not implemented in this version | Not implemented in this version | `ModelFeatureTests` |
| Full-text Use, single delivery, hide after Use | Real Use button with fake OS delivery | Real Use button with fake OS delivery | Private-board copy and missing-target fallback; successful native Use is manual |
| Wrong window, clipboard change, interruption, shutdown during paste | `PasteServiceTests` with fake platform | Same | Missing-target fallback automated; native delay/focus race scenarios manual |
| Native destination context lifetime | `NativeFocusChecks`, `-Xcheck:jni` | Same | Drawer/model lifetime and paste stop checks; AX references need native profiling |
| Shortcut event parsing, debounce, missing helper, helper exit | `HotkeyProtocolTests` | `HotkeyProtocolTests` | Actual Carbon shortcut registration requires manual testing |
| Variable-height masonry and responsive columns | `SwingFeatureTests` | `FxFeatureTests` | `ViewDrawerFeatureTests` uses the production layout calculation |
| Light/dark theme and drawer pin persistence | Settings and palette tests | Settings plus controller/style changes | Settings/model plus palette tests |
| Outside-click dismissal, click-inside latch, close races | `SwingDrawerChecks` | `DrawerChecks` | `SwiftDrawerChecks` |
| Header drag, docking on both edges, outer-edge arrow | `SwingInteractionTests` | `FxInteractionTests` | `ViewDrawerFeatureTests` |
| Resize, bounds, default size, reset, save callbacks | Settings and interaction cases | Settings and interaction cases | Storage and view/drawer cases |
| Persisted drawer position | Not implemented in this version | `PositionTests` and docking callback | Storage and docking callback tests |
| Hover delay/cancellation and hover-out behavior | Real pointer acceptance check below | Real pointer acceptance check below | `ViewDrawerFeatureTests` calls the real hover handler |
| Shutdown, repeated start/stop, timers and disposable windows | Drawer, watcher, helper, paste tests | Drawer, watcher, helper, paste tests | 200 model timer cycles, 12 drawer cycles, weak-reference release checks |
| Cross-version history, Unicode pins, pin order | Combined runner | Combined runner | Combined runner |

The combined runner checks every history producer/consumer pair, all nine lock holder/contender combinations, and reacquisition after each holder exits.

The tests exercise real policies/controllers/views where practical. A small number of Java controller fixtures use reflection to assemble the UI without starting global clipboard monitoring or the tray. The clipboard and paste seams are package-private; the application still uses the normal macOS adapters by default.

## Manual acceptance suite

Perform this on a test macOS account with disposable text. Run one version at a time and record the version, commit/source snapshot, macOS version, JDK/JavaFX or Swift version, screen setup, date, result, and evidence. Use the IDs below to match the skipped `manual.*` results. These procedures are repeatable tests, not claims that this run passed them.

### `paste.real-app-multiwindow-spaces`

1. Open two TextEdit documents and two browser windows with editable fields. Copy distinct harmless markers from each.
2. Select a text field, open nashclip by hover, tray, and hotkey in turn, then click Use.
3. Expect the full selected clip in the original field and the configured drawer-close behavior. Confirm other windows remain unchanged.
4. Repeat after moving between Spaces/fullscreen and after closing the destination app/window.
5. Change focus or clipboard during the restore delay. Expect refusal when verification fails; no unrelated text should be pasted.
6. Test an app that does not expose a focused AX window. Expect manual-paste fallback with the selected clip still copied.

### `permissions.denied-revoked-granted`

1. Deny Accessibility permission to the test process. Use should still copy the clip and report why automatic paste is unavailable.
2. Grant permission, relaunch, and verify paste in a disposable document.
3. Revoke permission while running. Expect graceful failure without stuck Use controls, uncaught errors, or permanently pressed modifiers.
4. Check outside-click detection failure messaging and arrow dismissal when the OS blocks event monitoring.

### `hotkey.native-registration-conflict`

1. With another app focused, press Control+Option+V. Expect one open action; holding the keys should not flood the UI.
2. Use a controlled competing shortcut registration. Expect a visible unavailable/conflict status and working tray/arrow fallback.
3. Quit nashclip and verify the shortcut can be registered again. Repeat launch/quit ten times and check for leftover helper processes.

### `tray.menu-and-quit`

1. Exercise Show, pin/unpin, theme, settings, size, and Quit from the actual tray menu. Verify menu state matches the panel and saved settings.
2. Verify **−** hides to the edge while capture continues; **×** and Quit stop capture and remove the handle/tray icon.
3. Quit during a pending Use, animation, and resize. No late key events or windows should appear.
4. Swift: Clear History removes ordinary clips, keeps pins, and leaves the system clipboard unchanged.

### `dialogs.save-cancel-invalid`

1. Open settings, change fields, and Cancel. Expect no saved setting changes.
2. Save a valid history limit, theme, and drawer pin. Restart and verify them.
3. Enter empty, nonnumeric, negative, and out-of-range limits/sizes. Expect validation and intact previous data.
4. Save a custom window default, resize, then reset. Expect the saved default. Cancel both size and settings dialogs and check for Accessibility stack traces.

### `displays.drag-dock-unplug`

1. Hover briefly over the arrow, then leave before the delay. It should remain closed. Hover long enough to open; moving out should leave it open.
2. Open by hover and click outside: it should close. Open again, click inside, then outside: it should remain until arrow dismissal. Repeat pinned and unpinned.
3. Drag using both the header and arrow. Dock left/right and check the arrow stays at the outer edge. Buttons must not initiate header dragging.
4. Resize from each supported edge, test minimum/maximum sizes, save defaults, and restart. JavaFX/Swift restore position; Swing currently does not.
5. Repeat on multiple displays, negative coordinates, scaling changes, display removal, and fullscreen/Spaces. Record unreachable handles or windows as defects.

### `accessibility.visual-keyboard-voiceover`

Check both themes, long Unicode text, five pinned clips, empty history, maximum retained history, narrow windows, scrolling, pin colour, and notice overlays. Verify labels and focus with keyboard and VoiceOver. Check notice expiry at 2, 3, 4, and 5 seconds, manual dismissal at 0, and replacement by a newer oversized-copy notice. A dismissed notice must not add a history tile.

### `soak.native-memory`

Run at least an hour of capture, Use, pin/unpin, delete, theme changes, drawer movement, and resize. Compare post-warmup live objects, native allocations, descriptors, threads, windows, and helper processes. Use Instruments for Swift/native objects and JVM tooling for Java. Include slow/read-only/full storage and rapid copying. Report monotonic retained-resource growth separately from stable runtime caches. Profiling output can contain test clipboard text.

### `release.clean-install`

Build and install on a clean test account for each supported architecture/runtime and minimum macOS version. Verify startup, signing/notarization as applicable, stable permissions after relaunch/update, migration, uninstall behavior, and lock rejection when another version is running. The automated compile check is not distribution certification.

## Add a regression

1. Add a small case that reproduces the defect using disposable input. Assert a user-visible outcome, persisted result, or resource lifecycle condition.
2. Keep platform effects behind the existing test seams. Do not add real keyboard events or use the general clipboard in an automated test.
3. Put version-specific Java cases in that version's `tests` folder and Swift cases in `nashclip-swift/Tests`. Shared Java fixtures live in `tests`; the runner supplies the JavaFX package declaration.
4. Register new test programs in `scripts/test.py`. `TestSupport.run` produces named results; use `finish()` so failures return a nonzero exit code.
5. Use a bounded wait for asynchronous state. Fixed delays are appropriate only when checking a minimum duration, such as notice lifetime.
6. Update this coverage table if the new case automates a previously manual scenario. Do not remove the manual gate until the automated case verifies the same outcome.
