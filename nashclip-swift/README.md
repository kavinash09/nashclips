# nashclip for macOS (Swift)

See the [project guide](../README.md) for the motivation, everyday use, version comparison, and limitations.

For installation and removal, see [INSTALL.md](../INSTALL.md). Homebrew publishing is prepared in the [release guide](../RELEASING.md); no public tap or signed download is available yet. Builds include `LICENSE` and `NOTICE` in `nashclip.app/Contents/Resources` so attribution remains with the installed app.

This is a native macOS version of nashclip. SwiftUI draws clipboard tiles in light yellow and cream or dark charcoal and gold; AppKit manages the menu bar icon and sliding edge drawer. It watches copied text, keeps a limited history, and lets you **Use** or delete a clip. The drawer supports Control + Option + V, delayed hover opening, pinning, dragging to either screen edge, and remembered placement. The menu bar includes Settings and Clear History.

## Build and run

You need macOS 13 or later and Xcode Command Line Tools. Full Xcode is optional.

```sh
cd nashclip-swift
sh run.sh
```

`run.sh` builds and opens `build/nashclip.app`. Run `sh build.sh` to build without opening it. The source is also a Swift package in `Package.swift`, which you can open in Xcode if you install it later. This Mac's default Command Line Tools SDK is newer than its Swift compiler, so `build.sh` selects the compatible installed SDK. Set `NASHCLIP_SDK` to a different SDK path if your toolchain changes.

Quit the Java Swing and JavaFX versions before running this app. All three share the same history and settings files, and only one can register the global shortcut at a time.

## What runs where

| File | Responsibility |
| --- | --- |
| `Main.swift` | Starts a menu bar app without a Dock icon. |
| `AppController.swift` | Connects the menu, UI actions, shortcut, and services. |
| `ClipboardModel.swift` | Watches the clipboard and updates limited history. |
| `Views.swift` | Draws tiles, the responsive column layout, header, footer, and handle. |
| `DrawerController.swift` | Slides, pins, and drags the two native windows. |
| `GlobalHotkey.swift` | Registers Control + Option + V with macOS. |
| `PasteCoordinator.swift` | Remembers the previous app and focused window, then sends Command + V. |
| `Storage.swift` | Reads and writes settings, Java-compatible history, and Swift drawer placement. |

```text
NSPasteboard → ClipboardModel → SwiftUI tiles
                           ↘ history.properties

global shortcut → AppController → DrawerController
Use button → clipboard → close drawer → PasteCoordinator → previous window
```

## Permissions and files

To paste automatically into another app, allow **nashclip** in **System Settings → Privacy & Security → Accessibility**. If permission is missing, **Use** still copies the text, so you can paste it manually. The app uses the focused window captured before opening the drawer to restore the right window where macOS permits it.

Switch themes with the moon/sun button, the menu bar **Dark Mode** item, or **Settings…**. The choice is stored as `"theme": "light"` or `"theme": "dark"` in shared settings. The app reads and writes the Java versions' `~/.nashclip/settings.json` and `~/.nashclip/history.properties`. It saves its own edge and height in `~/.nashclip/swift-position.json`. Drag the inner side edge to resize width or the top/bottom edge to resize height; the new size is saved on release. You can also use the header size button or menu bar **Window Size…**. **Save as default** changes the target for **Reset to default**; the initial default is 760 × 340 pixels. All three versions share `~/.nashclip/window-size.properties`. Files are local and written with private permissions. History contains readable copied text, so avoid copying secrets while nashclip is running. Use **Quit nashclip** in the menu bar to stop watching the clipboard.

For an isolated test, set `NASHCLIP_DATA_DIR` to another directory before launching the executable. This app currently stores text only.

**Author:** Avinash Kumar. Licensed under the [Apache License 2.0](../LICENSE).

## Audit controls and limits

The header **−** hides the drawer while capture continues; **×** quits. Click **Live / Paused** to pause capture for this session. Restart resumes capture. Updated variants prevent simultaneous use of the same data directory.

`historyLimit` accepts 1–500. Clips over 65,536 UTF-16 units are skipped, retained history has a 1,048,576-unit budget, and previews show up to 1,000 units. **Use** copies the full accepted clip. See the [production audit](../docs/production-audit.md) for test evidence, recovery behavior, and remaining release blockers.

## Pin individual clips

Click a tile's pin icon to keep its text at the top. Up to **five** clips can be pinned; unpin one before adding a sixth. Pins persist in the shared `~/.nashclip/history.properties` file and survive ordinary-history eviction and restarts. They are separate from the header pin, which holds the drawer open.

The history count limit applies to ordinary clips; pins use five additional slots within the same total-text budget. Deleting a pinned tile deletes its pin too. Swift's **Clear History** keeps pinned clips. Pin/unpin changes are applied only after a successful save. Updated builds read old history and write version 2, which older builds cannot read.

## Tests

Run `sh test.sh` from this folder, or `sh nashclip-swift/test.sh` from the repository root. Add `--headless` for core checks, or `--only 'TestProgramName'` for one test program. The default suite includes desktop tests with disposable windows and private/fake clipboards.

The runner writes JSON, JUnit XML, and logs to `test-results/latest` at the repository root. See the [testing guide](../docs/testing.md) for prerequisites, the feature matrix, and manual macOS acceptance checks.
