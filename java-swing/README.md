# nashclip (Java Swing)

See the [project guide](../README.md) for the motivation, everyday use, version comparison, and limitations.

nashclip keeps a small history of copied text in a macOS drawer. Copy text in another app, open the drawer with **Control + Option + V** or the menu bar icon, and press **Use** to paste an entry into the last active app. You can delete individual entries, pin the drawer open, and drag it by its handle or header.

## Build and run

You need macOS, JDK 17 or newer, and Xcode Command Line Tools (`xcode-select --install`). From this folder:

```sh
sh run.sh
```

`run.sh` compiles the Java classes and the macOS shortcut helper and JNI window-focus bridge, then starts `Main`. To build without launching, run `sh build.sh`. If you open `java-swing` in VS Code, use **Run nashclip (Java 17)**. The project root has a matching launch configuration if you open that folder instead. Build once before using the VS Code launch button if `native/nashclip-hotkey` or `native/libnashclip-focus.dylib` is absent.

The helper and `GlobalHotkey` expect the working directory to be `java-swing`. If you run Java yourself, use:

```sh
java -cp out Main
```

For automatic paste, allow the Java process in **System Settings → Privacy & Security → Accessibility**. The global shortcut and outside click detection use `native/hotkey.m`. If macOS blocks a feature, check the app's footer or terminal output.

## Find your way around

| File | Responsibility |
| --- | --- |
| `src/Main.java` | Starts the app. |
| `src/NashClip.java` | Connects history, window, settings, tray, and actions. Start reading at `run()`. |
| `src/ClipboardWatcher.java` | Checks the system clipboard once per second and reports new text. |
| `src/HistoryGrid.java` | Places variable height tiles in the shortest of up to three columns. |
| `src/ClipboardTheme.java` | Colors, fonts, and reusable Swing controls. |
| `src/EdgeDrawer.java` | Slides, pins, and drags the window at either screen edge. |
| `src/GlobalHotkey.java` | Reads shortcut and click events from the native helper. |
| `src/PasteService.java` | Freezes the captured window, restores and verifies it, checks clipboard text, then sends Command + V. |
| `src/NativeFocus.java`, `native/focus.m` | Own and release native Accessibility window references through JNI. |
| `src/AppSettings.java` | Reads and writes settings. |
| `src/HistoryStore.java` | Reads and writes saved clips. |
| `src/WindowSizeStore.java` | Saves the current and default drawer size. |
| `native/hotkey.m` | Registers the macOS shortcut and watches global clicks. |
| `examples/LocalClipboardDemo.java` | Separate learning exercise; the app does not use it. |

The main flow is:

```text
system clipboard → ClipboardWatcher → NashClip → history model
                                             ↘ HistoryGrid → tiles
                                             ↘ HistoryStore → disk

Control+Option+V → native helper → GlobalHotkey → NashClip → EdgeDrawer
Use button → NashClip → clipboard + PasteService → NativeFocus → captured window
```

Swing components and the history model change on the event dispatch thread. The shortcut reader and focus monitor run on background threads. `NashClip` sends their UI updates back with `SwingUtilities.invokeLater`. Automatic paste runs on another thread so its macOS calls and delays do not freeze the drawer.

## Settings and privacy

The app creates these files in your home directory:

```text
~/.nashclip/settings.json
~/.nashclip/history.properties
~/.nashclip/window-size.properties
```

Switch themes with the moon/sun button in the header, the menu bar **Dark Mode** item, or **Settings…**. All three versions share the saved theme. Drag the inner side edge to resize width or the top/bottom edge to resize height; the new size is saved on release. You can also use the header size button or menu bar **Window Size…**. **Save as default** changes the size to which **Reset to default** returns. The initial default is 760 × 340 pixels. The grid shows one, two, or three columns according to the width. You can also edit `settings.json` while the app is closed:

```json
{
  "historyLimit": 40,
  "pinned": false,
  "theme": "light"
}
```

Set `theme` to `"light"` or `"dark"`. Restart after editing the file. Lowering `historyLimit` keeps the newest clips. `pinned` remembers whether the drawer stays open. Clips are saved as readable text in `history.properties`; avoid copying secrets while nashclip is running. Quit from the menu bar to stop watching the clipboard. The app currently supports text, not images or rich content.

## A good reading order

1. Read `Main.main()`'s call into `NashClip.run()`, then follow `addCopiedText()`.
2. Find where `HistoryGrid.refresh()` chooses the shortest column.
3. Follow the **Use** button from `createTile()` to `PasteService.paste()`.
4. Follow the shortcut from `native/hotkey.m` through `GlobalHotkey` to `EdgeDrawer`.

Change one thing at a time and run `sh build.sh` after each change. For example, change the clipboard check interval in `ClipboardWatcher`, or change the tile width in `NashClip`.

**Author:** Avinash Kumar. Licensed under the [Apache License 2.0](../LICENSE).

## Audit controls and limits

The header **−** hides the drawer while capture continues; **×** quits. Click **Live / Paused** to pause capture for this session. Restart resumes capture. Updated variants prevent simultaneous use of the same data directory.

`historyLimit` accepts 1–500. Clips over 65,536 UTF-16 units are skipped, retained history has a 1,048,576-unit budget, and previews show up to 1,000 units. **Use** copies the full accepted clip. See the [production audit](../docs/production-audit.md) for test evidence, recovery behavior, and remaining release blockers.

## Pin individual clips

Click a tile's pin icon to keep its text at the top. Up to **five** clips can be pinned; unpin one before adding a sixth. Pins persist in the shared `~/.nashclip/history.properties` file and survive ordinary-history eviction and restarts. They are separate from the header pin, which holds the drawer open.

The history count limit applies to ordinary clips; pins use five additional slots within the same total-text budget. Deleting a pinned tile deletes its pin too. Swift's **Clear History** keeps pinned clips. Pin/unpin changes are applied only after a successful save. Updated builds read old history and write version 2, which older builds cannot read.

## Tests

Run `sh test.sh` from this folder, or `sh java-swing/test.sh` from the repository root. Add `--headless` for core checks, or `--only 'TestProgramName'` for one test program. The default suite includes desktop tests with disposable windows and private/fake clipboards.

The runner writes JSON, JUnit XML, and logs to `test-results/latest` at the repository root. See the [testing guide](../docs/testing.md) for prerequisites, the feature matrix, and manual macOS acceptance checks.
