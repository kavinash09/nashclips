# Installing nashclip

**Current status:** source builds and Homebrew source release 1.0.1 are published. A signed downloadable release remains pending. For maintainers preparing a binary release, see [RELEASING.md](RELEASING.md).

## Homebrew source release

With Homebrew and compatible Apple Command Line Tools installed:

```sh
brew tap kavinash09/nashclip https://github.com/kavinash09/nashclips.git
brew install --formula kavinash09/nashclip/nashclip
nashclip
```

If the tools are missing, run `xcode-select --install` and finish installation first.
This formula compiles the Swift app locally and launches it from Homebrew's
directory, rather than `/Applications`. No paid Apple Developer membership is
needed. It is a source release, not a notarized download. Only Apple Silicon
has been validated locally; other supported combinations still need testing.
See [the formula guide](homebrew/README.md) for updates and removal. Quit other
variants before launching and enable Accessibility for automatic paste.

## Requirements

| Version | Build requirements | Output |
| --- | --- | --- |
| Swift | macOS 13+, a compatible Swift compiler and macOS SDK from Xcode Command Line Tools | Native `nashclip.app`; no Java required |
| Swing | macOS, JDK 17+, Xcode Command Line Tools | Java classes and native helpers |
| JavaFX | macOS, JDK 17+, Maven, Xcode Command Line Tools | Java classes, JavaFX dependencies, and native helpers |

Java integration is macOS-specific despite using cross-platform UI frameworks. The Swift build targets the build machine's CPU architecture. Intel and Apple Silicon downloads must match the user's Mac unless a release explicitly provides a universal build. macOS 13 is the Swift deployment target; see each release's test evidence for versions actually validated.

## Build from source today

Download this repository's source ZIP or clone its URL using Git, then open Terminal in the extracted repository root. The directory name may be `nashclip` or the original `mcopier`.

Install Apple's Command Line Tools if they are missing:

```sh
xcode-select --install
```

### Native Swift

```sh
swift --version
xcrun --sdk macosx --show-sdk-path
sh nashclip-swift/run.sh
```

This builds and opens `nashclip-swift/build/nashclip.app`. To build without launching:

```sh
sh nashclip-swift/build.sh
```

For development, launch this copy in place. Local builds are ad hoc signed and are not the signed, notarized downloads intended for public distribution. Full Xcode is optional for local builds; use a compiler and SDK that work together. The [Swift README](nashclip-swift/README.md) explains `NASHCLIP_SDK`.

### Java Swing

```sh
java -version
javac -version
sh java-swing/run.sh
```

### JavaFX

```sh
java -version
javac -version
mvn -version
sh java-fx/run.sh
```

JavaFX's first run downloads Maven dependencies. Use the run scripts to build and locate the native shortcut and focus helpers. See the [Swing](java-swing/README.md) and [JavaFX](java-fx/README.md) guides for IDE setup and details. The JavaFX JAR is not a standalone installer.

## First launch

1. Quit other nashclip variants; they share a data directory and enforce a single-instance lock.
2. Find the edge arrow or the menu bar icon. Swift runs without a Dock icon.
3. Open **System Settings → Privacy & Security → Accessibility** and enable **nashclip** for Swift automatic paste. The Java variants need permission for their Java process; the two permissions are separate.
4. Copy disposable text, open the drawer with **Control + Option + V**, then click **Use** with a destination text field previously selected.

If Use copies but does not paste, check the footer and Accessibility permission, then restart the relevant app. Manual **Command + V** remains available. Granting permission alone does not verify that the destination window was restored correctly.

Clipboard history is saved as readable text in `~/.nashclip`. Pause capture or quit before copying secrets. See [data and privacy](README.md#local-data-and-privacy).

## Homebrew installation after publication

The maintainer will replace `OWNER` with the actual GitHub account in the published release instructions. These commands are templates until the tap exists:

```sh
brew install --cask OWNER/nashclip/nashclip
open /Applications/nashclip.app
```

The cask installs the native Swift app. End users need Homebrew and a compatible Mac; they do not need development tools. Homebrew has its own [supported macOS requirements](https://docs.brew.sh/Installation), separate from the app's deployment target.

For a signed downloadable ZIP, obtain the asset for your architecture from this repository's Releases page, check it against the release's `SHA256SUMS`, extract it, and drag `nashclip.app` to Applications. Quit an existing copy before replacing it. No signed download is available yet.

## Update and uninstall

For source builds, quit the app, update the source checkout, then rerun the corresponding run script. For a published Homebrew installation:

```sh
brew update
brew upgrade --cask OWNER/nashclip/nashclip
brew uninstall --cask OWNER/nashclip/nashclip
```

Quit before updating. For a manually installed app, quit and move the app bundle to Trash. Source builds can be removed with their build directories after quitting.

Removing the app preserves `~/.nashclip` and the legacy `~/.mcopier` backup. To remove saved history and settings, quit **all three variants**, open those directories in Finder with **Go → Go to Folder**, and deliberately delete the files you no longer want. Do not delete an active instance lock. Deleting history does not clear the system clipboard or other backups, and is not secure erasure.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No normal app window or Dock icon | Use the edge arrow, menu bar, or Control + Option + V. |
| Already-running notice | Quit the other variant. Do not bypass the shared lock. |
| Use only copies | Check Accessibility for the correct process and read the footer. |
| Shortcut does nothing | Another app may own Control + Option + V; try the menu bar. |
| Swift compiler/SDK mismatch | Select compatible Command Line Tools or set `NASHCLIP_SDK`. |
| Missing Java native library/helper | Start through the variant's `run.sh`. |
| Download rejected by Gatekeeper | Use an official signed release and report the asset/version; do not disable Gatekeeper. |

Report reproducible problems through this repository's Issues page using synthetic text. See [CONTRIBUTING.md](CONTRIBUTING.md).
