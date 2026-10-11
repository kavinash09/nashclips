# nashclip (JavaFX)

See the [project guide](../README.md) for the motivation, everyday use, version comparison, and limitations.

This is a JavaFX version of [nashclip's Swing app](../java-swing/README.md). It watches copied text, keeps a limited history, displays clips in up to three variable-height columns, and lets you delete or **Use** a clip. The yellow and cream drawer slides from either screen edge. You can drag its handle or header, pin it open, and open it with **Control + Option + V** or the menu bar icon. After a drag, the drawer remembers its edge and height for the next launch.

## Run it

You need macOS, JDK 17, Maven, and Xcode Command Line Tools. From `java-fx`:

```sh
sh run.sh
```

The script builds the macOS shortcut helper and JNI window-focus bridge and runs the JavaFX app. On the first run, Maven downloads JavaFX and its macOS libraries to `target/m2`, so startup may take a little longer. To compile without opening a window:

```sh
mvn -s maven-settings.xml -Dmaven.repo.local=target/m2 compile
```

The local Maven settings use public Maven Central because this Mac's user settings point to an internal server. If you open `java-fx` in VS Code, run the **Run nashclip JavaFX** task or use the Maven explorer's `javafx:run` goal after running `sh native/build.sh` to build both native helpers.

Quit the Swing version before starting this one: only one app can own the shortcut, and both versions write the same history file. macOS automatic paste needs Accessibility access for the Java process under **System Settings → Privacy & Security → Accessibility**.

## How the pieces fit

| File | Job |
| --- | --- |
| `Launcher.java` | Starts JavaFX from Maven. |
| `NashClipApp.java` | Connects actions, history, settings, tray menu, and services. Start reading at `start()`. |
| `ClipboardWatcher.java` | Checks the system clipboard once per second with a JavaFX `Timeline`. |
| `HistoryGrid.java` | Places each tile below the shortest of up to three columns. |
| `ClipboardTile.java` | Shows one clip and reports Use or Delete clicks. |
| `EdgeDrawer.java` | Manages the sliding panel, hover delay, pinning, and dragging. |
| `DrawerPositionStore.java` | Saves and restores the drawer's screen, side, and height. |
| `WindowSizeStore.java` | Saves the current and default drawer size. |
| `GlobalHotkey.java` | Reads shortcut and outside-click events from `native/hotkey.m`. |
| `PasteService.java` | Freezes the captured window, restores and verifies it, checks clipboard text, then sends Command + V. |
| `NativeFocus.java`, `native/focus.m` | Own and release native Accessibility window references through JNI. |
| `AppSettings.java`, `HistoryStore.java` | Read and save settings and clipboard history. |
| `style.css` | The light cream and dark charcoal themes. |

```text
system clipboard → ClipboardWatcher → NashClipApp → history list → HistoryGrid → tiles
                                                  ↘ HistoryStore → disk

shortcut → native helper → GlobalHotkey → NashClipApp → EdgeDrawer
Use button → NashClipApp → clipboard + PasteService → NativeFocus → captured window
```

JavaFX controls and the history list change on the JavaFX application thread. The shortcut reader and focus monitor run in background threads and use `Platform.runLater` to update the UI. Pasting runs on another thread so its macOS calls do not freeze the drawer.

## Settings and privacy

All three versions use `~/.nashclip/settings.json` and `~/.nashclip/history.properties`. JavaFX also saves its drawer placement in `~/.nashclip/java-fx-position.properties`. Switch themes with the moon/sun button, the menu bar **Dark Mode** item, or **Settings…**. Drag the inner side edge to resize width or the top/bottom edge to resize height; the new size is saved on release. You can also use the header size button or menu bar **Window Size…**. **Save as default** updates the size used by **Reset to default**. The initial default is 760 × 340 pixels, and the size is shared in `~/.nashclip/window-size.properties`. You can also edit `settings.json` while the app is closed:

```json
{
  "historyLimit": 40,
  "pinned": false,
  "theme": "light"
}
```

Set `theme` to `"light"` or `"dark"`. Restart after editing the file. History is saved as readable text with private file permissions. Avoid copying secrets while nashclip is running; use the menu bar's **Quit nashclip** item to stop watching. This version handles text only.

JavaFX 21 was chosen because it [supports JDK 17](https://docs.oracle.com/en/java/java-components/javafx/21/javafx-users-guide/supported-jdk-versions.html). OpenJFX [documents Maven setup](https://openjfx.io/openjfx-docs/maven), including platform libraries and the `javafx:run` goal.

**Author:** Avinash Kumar. Licensed under the [Apache License 2.0](../LICENSE).

## Audit controls and limits

The header **−** hides the drawer while capture continues; **×** quits. Click **Live / Paused** to pause capture for this session. Restart resumes capture. Updated variants prevent simultaneous use of the same data directory.

`historyLimit` accepts 1–500. Clips over 65,536 UTF-16 units are skipped, retained history has a 1,048,576-unit budget, and previews show up to 1,000 units. **Use** copies the full accepted clip. See the [production audit](../docs/production-audit.md) for test evidence, recovery behavior, and remaining release blockers.

## Pin individual clips

Click a tile's pin icon to keep its text at the top. Up to **five** clips can be pinned; unpin one before adding a sixth. Pins persist in the shared `~/.nashclip/history.properties` file and survive ordinary-history eviction and restarts. They are separate from the header pin, which holds the drawer open.

The history count limit applies to ordinary clips; pins use five additional slots within the same total-text budget. Deleting a pinned tile deletes its pin too. Swift's **Clear History** keeps pinned clips. Pin/unpin changes are applied only after a successful save. Updated builds read old history and write version 2, which older builds cannot read.

## Tests

Run `sh test.sh` from this folder, or `sh java-fx/test.sh` from the repository root. Add `--headless` for core checks, or `--only 'TestProgramName'` for one test program. The default suite includes desktop tests with disposable windows and private/fake clipboards.

The runner writes JSON, JUnit XML, and logs to `test-results/latest` at the repository root. See the [testing guide](../docs/testing.md) for prerequisites, the feature matrix, and manual macOS acceptance checks.
