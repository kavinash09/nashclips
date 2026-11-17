# nashclip

**Author:** Avinash Kumar  
**License:** [Apache License 2.0](LICENSE)

nashclip is a small clipboard history app for macOS inspired by the quick recall of Windows **Win + V**. It keeps recently copied text within reach in a drawer at the edge of the screen. The project also explores the same app through three UI approaches: Java Swing, JavaFX, and native SwiftUI with AppKit.

**Status:** active development. Local source builds and a Homebrew source-preview formula are available in this repository; a signed binary release remains pending.

[Installation](INSTALL.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Security](SECURITY.md) · [Release guide](RELEASING.md)

## Quick start

On a Mac with compatible Xcode Command Line Tools installed, open Terminal in this repository and run:

```sh
sh nashclip-swift/run.sh
```

Open the drawer from the edge arrow, menu bar, or **Control + Option + V**. Enable Accessibility for **nashclip** to use automatic paste. For Java variants, prerequisites, updates, and uninstall instructions, see [INSTALL.md](INSTALL.md).

## Why this project

Copying a second piece of text normally replaces the first item on the clipboard. nashclip keeps a limited local history so you can return to an earlier snippet, copy it again, and paste it into your work. Building the same workflow in three frameworks makes their differences in UI design, event handling, macOS integration, and packaging easier to understand.

## What it does

- Watches the system clipboard for new text and shows recent clips in up to three columns.
- Copies a selected clip back to the clipboard when you press **Use**, then attempts to paste it into the previous app.
- Lets you pin up to five clips, delete individual clips, and set a maximum ordinary-history size.
- Opens from the edge handle, the menu bar icon, or **Control + Option + V**.
- Lets you drag the drawer to either side of the screen, change its size, pin it open, and switch between light and dark themes.
- Saves text history and settings locally in `~/.nashclip`.

The **header pin** keeps the drawer visible. Each **tile pin** saves that clip at the top of the history, with a maximum of five pinned clips. The [JavaFX](java-fx/README.md) and [Swift](nashclip-swift/README.md) versions also remember the drawer's screen position; Swift adds a **Clear History** menu item.

## Choose a version

| Version | Requirements | Run from this repository | Details |
| --- | --- | --- | --- |
| [Java Swing](java-swing/README.md) | macOS, JDK 17+, Xcode Command Line Tools | `sh java-swing/run.sh` | Java UI with native shortcut and window-focus helpers. |
| [JavaFX](java-fx/README.md) | macOS, JDK 17+, Maven, Xcode Command Line Tools | `sh java-fx/run.sh` | JavaFX UI; Maven downloads dependencies on first run. |
| [Native Swift](nashclip-swift/README.md) | macOS 13+, Xcode Command Line Tools | `sh nashclip-swift/run.sh` | Builds and opens `nashclip-swift/build/nashclip.app`. Full Xcode is optional. |

Open Terminal in the repository root before running one of these commands. You can check your local tools with `java -version`, `javac -version`, `mvn -version`, `swift --version`, and `xcode-select -p`. Each version's README has build commands and a guide to its source files.

**Run one version at a time.** Updated builds enforce a shared data-directory lock. Quit older builds before starting one of these versions; old builds do not honor this lock.

## Homebrew distribution

The native Swift app has a [source-preview formula](homebrew/README.md). It builds on your Mac using Apple's Command Line Tools, with no paid Apple Developer membership needed. Once the formula commit is pushed, install it with:

```sh
brew tap kavinash09/nashclip https://github.com/kavinash09/nashclips.git
brew install --formula kavinash09/nashclip/nashclip
nashclip
```

Install compatible Command Line Tools first with `xcode-select --install` if needed. This development preview lives inside Homebrew's directory, not `/Applications`. Quit other variants before launching; automatic paste still requires Accessibility permission. History remains local plaintext. Only the local Apple Silicon build has been validated; the outstanding app acceptance checks remain open.

The separate [binary cask tooling and publishing guide](docs/homebrew.md) generates a release ZIP, checksum, and cask. That prebuilt distribution still requires Developer ID signing, notarization, and release acceptance testing under this project's release policy. No signed binary package has been published. A local package-layout preview is available with `python3 scripts/package-homebrew.py --preview --output dist/homebrew-preview` after building the Swift app.

See the [Homebrew audit](docs/homebrew-audit.md) for packaging fixes, verified checks, and remaining publication requirements.

## How to use it

1. Start one version. Look for the narrow arrow at the screen edge or the nashclip icon in the macOS menu bar.
2. Copy some text in another app. New text usually appears in nashclip within one second.
3. Open the drawer with the arrow, menu bar icon, or **Control + Option + V**.
4. Press **Use** on a tile to copy its text and attempt to paste it into the app you were using. If automatic paste is unavailable, select the destination and press **Command + V** yourself.
5. Use a tile’s pin button to pin or unpin its text (maximum five). Pinned clips appear first, survive restarts, and are kept when ordinary history is trimmed. The header shows how many pin slots are used. Use a tile’s delete button to remove it, including a pinned clip. Use the header pin to keep the drawer open. Drag the handle or header to move the drawer, and use the arrow to close it. When open, the arrow stays at the outer screen edge.
6. Change the light or dark theme with the moon/sun button, the menu bar **Dark Mode** item, or **Settings**. Settings also lets you change the history limit.
7. Click **Live / Paused** to pause capture for the session. **−** hides the drawer to its edge handle and keeps capture running. **×** quits and stops capture. Restart resumes capture.
8. Drag the drawer's inner side edge to change its width: the left edge when docked right, or the right edge when docked left. Drag its top or bottom edge to change height. The new size is saved when you release. You can also use the header size button or menu bar **Window Size…** item to enter dimensions. **Save as default** updates the size used by **Reset to default**. The initial default is 760 × 340 pixels. Smaller widths show fewer tile columns.

The Swift version can clear unpinned clips from its menu bar **Clear History** item; pinned clips are kept. In the Java versions, delete clips individually.

### Automatic paste permission

Automatic paste controls another app and needs **Accessibility** permission. Open **System Settings → Privacy & Security → Accessibility** and enable the process running nashclip. The Java versions may appear as `java` or as the app that launched Java; the native version appears as `nashclip`. macOS may ask you to allow the renamed native app again. Restart the app after changing permission if paste still fails. **Use** still places the text on the clipboard when automatic paste is unavailable.

## Local data and privacy

All versions share these files in your home directory:

| File | Contents |
| --- | --- |
| `~/.nashclip/history.properties` | Pinned text plus ordinary history, saved together atomically. |
| `~/.nashclip/settings.json` | History limit, drawer pin state, and theme. |
| `~/.nashclip/window-size.properties` | Current size and user-saved default size, shared by all three versions. |

JavaFX saves its drawer position in `~/.nashclip/java-fx-position.properties`. Swift saves its position in `~/.nashclip/swift-position.json`.

On the first launch after this rename, nashclip copies existing files from `~/.mcopier` into `~/.nashclip`. The old folder stays in place as a backup. If the copy fails, the app continues using the old folder for that run and reports the error in the terminal. Close any older version before launching nashclip so both apps do not write separate histories.

Edit `settings.json` while nashclip is closed:

```json
{
  "historyLimit": 40,
  "pinned": false,
  "theme": "light",
  "sizeNoticeSeconds": 4
}
```

Set `theme` to `"light"` or `"dark"`; `historyLimit` must be a whole number from **1 to 500**. Restart nashclip after a manual edit. The limit applies to ordinary clips; the five pin slots are additional. Reducing the limit keeps the newest ordinary clips and all pinned clips.

**History is readable text on disk, without encryption.** The app can capture passwords, tokens, and other sensitive text if you copy them. Pause capture or quit nashclip before copying secrets. Resume skips content copied while paused. Deletion does not clear the system clipboard or legacy/backed-up files. There is no expiration policy.

New clips above 65,536 UTF-16 code units (128 KiB of UTF-16 text) are skipped. A red overlay shows the last rejected clip’s size without adding a tile or shifting the layout. Click **×** to dismiss it. Only the size is kept for the notice; rejected content is never added to history. If the drawer is hidden, the latest notice waits until you open it.

Set `sizeNoticeSeconds` to **2–5** for automatic dismissal (default **4**), or **0** to keep the notice until you dismiss it. Edit `~/.nashclip/settings.json` while the app is closed, then restart. The notice fades in and out; repeated Java polling of the same oversized clip does not restart it.

Total retained text, including pins, is capped at 1,048,576 units. Pinned text reserves its share first; oldest ordinary clips are evicted to fit. Copying text that is already pinned does not add a duplicate. Unpinning returns a clip to ordinary history, where normal limits apply.

Pin state is shared across all three updated versions. Existing version-1 history is read automatically; the next save uses version 2 to keep pin state and text in one file. Older builds cannot read version 2—use the updated builds when switching versions. Tiles show a preview of up to 1,000 units, while **Use** copies the complete accepted text. See the [audit](docs/production-audit.md) for file limits and recovery behavior.

## Production readiness

This build is **not yet production approved**. Read the [latest release verdict](docs/release-verdict.md) for fresh test/build results and shipping blockers, and the [production audit](docs/production-audit.md) for fixed defects and profiling procedures. Run `python3 scripts/audit-checks.py --lifecycle` for isolated storage, process, locking, and Swift timer checks. Run `python3 scripts/gui-checks.py` in a desktop session for drawer, private-clipboard, and native bridge lifecycle regressions; it does not send paste keystrokes.

## Test suites

Run `sh java-swing/test.sh`, `sh java-fx/test.sh`, or `sh nashclip-swift/test.sh` for one version. Run `python3 scripts/test.py` for all versions plus cross-version compatibility checks. Add `--headless` to skip desktop tests.

Results are written to `test-results/latest/summary.json` and `test-results/latest/junit.xml`, with individual logs. Tests use temporary data and private/fake clipboards; no real paste keystrokes are sent. See the [testing guide](docs/testing.md) for prerequisites, feature coverage, targeted runs, and the manual macOS acceptance suite.

For measured coverage, run `python3 scripts/setup-coverage.py` once, then `python3 scripts/test.py --check-coverage`. The gate requires 90% line coverage of the defined core and reports all production Java/Swift source separately. Run `python3 scripts/mutation-checks.py` to verify that important assertions detect seeded regressions. See the [test scrutiny report](docs/test-scrutiny.md) for measured results and remaining gaps.

## Project memory and developer walkthrough

Start with [AGENTS.md](AGENTS.md) for the current handoff and working guidance. The [project memory](docs/project-memory.md) records the development history, decisions, verified results, and pending work. The [architecture walkthrough](docs/architecture-walkthrough.md) maps all three implementations and follows capture, Use, drawer behavior, storage, and tests through the source.

## Current limitations

- **Text only.** Images, files, and rich clipboard formats are not stored.
- **Periodic checks.** Each version checks the clipboard about once per second. Several quick copies between checks can leave only the latest one in history. Copying the same text again may not create a new entry, and text already on the clipboard at startup is not added until you copy something new.
- **Paste target.** All versions try to restore and verify the captured window. If verification fails, Use leaves the text copied for manual Command+V. They cannot verify the exact text field or make focus/clipboard checks atomic with paste. Multiwindow, Spaces, and permission changes still need acceptance testing. Build the Java native helpers with `run.sh` or the build commands before using an IDE launch.
- **Local prototype.** There is no search, automatic launch at login, sync, or installer. The Java versions run from source; the Swift script builds a local `.app` bundle.
- **Permissions and shortcut conflicts.** macOS can block simulated paste or global click detection. **Control + Option + V** may be unavailable if another app has registered it.

## Ideas for the next iteration

Search, image support, and a distribution-signed app package would make useful learning milestones. These features are not implemented yet.

## Contributing and support

Use the repository's Issues page for reproducible bugs and feature requests. The templates ask for the app variant, environment, and steps using disposable text. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request and [SECURITY.md](SECURITY.md) for private vulnerability reporting. The [release guide](RELEASING.md) covers repository setup and publishing.

## License and author

Copyright © 2026 Avinash Kumar. Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for the project attribution.
