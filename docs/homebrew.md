# Distributing nashclip with Homebrew

## Source preview without a Developer ID certificate

On 2026-11-17 the user confirmed that they do not have an Apple Developer
membership and requested Homebrew publication using the existing Git client.
`Formula/nashclip.rb` now provides a personal-tap source build. The existing
GitHub project doubles as the tap; see [installation and maintenance](../homebrew/README.md).
This route requires compatible Apple Command Line Tools on each receiving Mac.
It compiles and ad hoc signs the app locally, preserves history on uninstall,
and supplies a `nashclip` launch command. It does not distribute a prebuilt app
or disable Gatekeeper. The formula pins source commit
`e4da8e80dc33c5ec1d081f8297c40c5574f08467` and its downloaded archive checksum.
The formula version `1.0-preview.1` identifies this development package; it does
not change the app's existing `1.0` bundle version or clear release blockers.

Publication and real-install verification for this route are recorded in
[project memory](project-memory.md). The remaining instructions below cover
the separate, future signed binary cask route. Their unpublished-cask status
does not describe the source formula.

## Signed binary cask route

Start with the repository setup and acceptance checklist in [RELEASING.md](../RELEASING.md). End-user build, update, and uninstall instructions are in [INSTALL.md](../INSTALL.md).

The Swift app can be distributed as a **Homebrew cask**. A cask downloads a prebuilt app and installs it in `/Applications`; users do not need Java, Swift, or Xcode. Start with your own GitHub tap, for example `OWNER/homebrew-nashclip`. Once published, users can install it with:

```sh
brew install --cask OWNER/nashclip/nashclip
```

`OWNER` is a placeholder for your GitHub account or organization. **This command is not live yet.** No tap or release has been published. Inclusion in the official `homebrew/cask` repository is a separate submission and review, subject to Homebrew's acceptance criteria.

The local packaging tool is `scripts/package-homebrew.py`. It packages the Swift app only. Swing and JavaFX still use their existing build/run scripts.

## 1. Try the package layout locally

From the project root:

```sh
sh nashclip-swift/build.sh
python3 scripts/package-homebrew.py --preview --output dist/homebrew-preview
```

The output contains a ZIP with `nashclip.app`, `LICENSE`, and `NOTICE`, a `SHA256SUMS` file, package metadata, and `Casks/nashclip.rb.example`. Preview mode accepts the local ad hoc signature and skips distribution checks. Its ZIP is labelled `preview` and its cask is a draft; do not publish it as a release.

The tool refuses to overwrite an existing output directory or write inside the source bundle. Choose a new directory for another run. It copies the bundle before packaging, prepares all outputs in temporary storage, then moves the complete result into place. It leaves the source build untouched and does not open the app, access clipboard history, install anything, or publish files.

The Swift build script assembles a fresh bundle to exclude stale resources, embeds `LICENSE` and `NOTICE` in `Contents/Resources`, and signs it before replacing the previous build. A compiler failure preserves the previous bundle. The packager checks the embedded files against the repository copies. Rebuild older bundles before signing; the ZIP retains top-level copies as well.

Only one Swift build may run in a checkout at a time. `build/.nashclip-build.lock` prevents overlapping builds from replacing each other's output. If a process is forcibly killed, confirm that no build is still running before removing that empty lock directory with `rmdir`. If replacement and restoration both fail, the script prints the preserved backup path for manual recovery instead of deleting it.

## 2. Build and sign a release

Required publishing inputs:

- A public project repository or homepage, a GitHub release location, and a tap repository.
- An Apple Developer membership and a **Developer ID Application** signing certificate.
- Apple's notarization tools and a configured Keychain profile for `notarytool`.
- Completed acceptance testing for the release, including automatic paste and a clean installation. See the [release verdict](release-verdict.md).

Set `CFBundleShortVersionString` and increment `CFBundleVersion` in `nashclip-swift/Info.plist` before building. The packager reads the version from the bundle; the examples below use the current `1.0` version.

The current build script builds for the host architecture. On Apple Silicon this produces **arm64 only**; an Intel build produces **x86_64 only**. The generated cask declares that restriction. A universal binary needs a separate, tested build workflow; detecting universal binaries in the packager does not make the current build universal.

Build, sign, and prepare an upload. Replace the certificate name with your actual identity:

```sh
sh nashclip-swift/build.sh
mkdir -p dist
codesign --force --options runtime --timestamp \
  --sign 'Developer ID Application: YOUR NAME (TEAMID)' \
  nashclip-swift/build/nashclip.app
codesign --verify --deep --strict nashclip-swift/build/nashclip.app
ditto -c -k --sequesterRsrc --keepParent \
  nashclip-swift/build/nashclip.app dist/nashclip-notarization.zip
xcrun notarytool submit dist/nashclip-notarization.zip \
  --keychain-profile nashclip-notary --wait
```

Create the `nashclip-notary` Keychain profile using Apple's instructions before submission. Keep passwords and signing keys out of the repository. Continue only when the notarization result is **Accepted**. If rejected, inspect the submission log and fix the reported cause.

Staple the ticket to the app:

```sh
xcrun stapler staple nashclip-swift/build/nashclip.app
xcrun stapler validate nashclip-swift/build/nashclip.app
spctl --assess --type execute --verbose=4 nashclip-swift/build/nashclip.app
```

Do not rebuild or edit the app after signing and stapling. A rebuild replaces the signature with an ad hoc signature; edits invalidate the seal. Test this hardened-runtime build before shipping it. Signing and notarization do not establish that paste or other app features work correctly.

## 3. Generate the release ZIP and cask

Replace `OWNER/PROJECT` below with your public project repository. Match the tag, version, and architecture to the actual build:

```sh
python3 scripts/package-homebrew.py \
  --output dist/homebrew-1.0-arm64 \
  --team-id YOURTEAMID \
  --url https://github.com/OWNER/PROJECT/releases/download/v1.0/nashclip-1.0-macos-arm64.zip \
  --homepage https://github.com/OWNER/PROJECT
```

Replace `YOURTEAMID` with your actual 10-character Apple Team ID. Release mode checks the signer and hardened runtime for every architecture in the executable, in addition to verifying all architecture signatures, the bundle seal, stapled notarization ticket, and local Gatekeeper assessment. It then creates:

```text
dist/homebrew-1.0-arm64/
├── nashclip-1.0-macos-arm64.zip
├── SHA256SUMS
├── package.json
└── Casks/nashclip.rb
```

The cask records the exact ZIP checksum, bundle version, minimum macOS version, architecture, and app name. The tool does not check whether the supplied URL exists or upload its output. Upload **that exact ZIP** at the supplied URL. Changing or recreating it requires recalculating the checksum and updating the cask.

URLs must be permanent HTTPS addresses without query strings, fragments, or placeholders; the download URL must end in the generated ZIP filename. The packager checks the actual executable's minimum macOS version for every architecture, executable permissions, and library/search paths. The current app uses only system libraries and no bundle symlinks; adding frameworks, third-party libraries, or a newer deployment target requires updating the packaging checks deliberately.

## 4. Publish your tap

1. Publish the ZIP and checksum with the matching project release.
2. Create a public GitHub repository called `homebrew-nashclip` under `OWNER`.
3. Copy the generated `Casks/nashclip.rb` into that repository and commit/push it. Include a README with installation instructions and attribution.
4. On a test Mac, tap and audit it:

   ```sh
   brew tap OWNER/nashclip
   brew style --cask OWNER/nashclip/nashclip
   brew audit --cask --online OWNER/nashclip/nashclip
   brew install --cask OWNER/nashclip/nashclip
   open /Applications/nashclip.app
   ```

Use a disposable macOS account for first-install and uninstall testing so its home directory has no personal clipboard history. Quit the development build before launching the installed app. Allow **nashclip** in **System Settings → Privacy & Security → Accessibility** when testing automatic paste. Homebrew installation does not grant that permission.

Verify installation, launch, capture, Use into a disposable destination, shortcut, drawer, settings, quit, upgrade, and uninstall. Users can update with `brew update` followed by `brew upgrade --cask OWNER/nashclip/nashclip`. Each update needs a new version, published artifact, and updated cask checksum.

The cask asks the app to quit during uninstall. It deliberately omits `zap` removal of `~/.nashclip` and `~/.mcopier`: those directories contain user data shared with the Java variants. Uninstalling the app preserves history and settings. Users may delete those directories themselves after quitting all variants if they intend to remove the data permanently.

## Verification and scope

```sh
python3 -m unittest discover -s tests -p test_homebrew_package.py -v
HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_ANALYTICS=1 HOMEBREW_DEVELOPER=1 \
  brew ruby scripts/check-homebrew-cask.rb dist/homebrew-preview
python3 scripts/test-homebrew-cask.py dist/homebrew-preview
```

These tests cover release guards, architecture/deployment metadata, URLs, checksums, embedded licensing, failure cleanup, preview labelling, and output protection. Signing commands are mocked in the unit suite; a successful test run does not certify a signed release. The second command loads the definition through Homebrew itself and checks version, checksum, URLs, platform requirements, app path, and quit-only uninstall behavior. It does not install anything. The third command also checks seven deliberately altered definitions, including destructive uninstall/zap instructions, without executing those instructions. CI runs this regression check too. Ruby syntax alone is insufficient to validate Homebrew's cask DSL.

Regenerate packages before running the updated checker: package metadata now includes the intended cask URL and homepage. Historical previews do not have all fields required by the stricter checks.

The [Homebrew audit](homebrew-audit.md) records the 2026-10-11 defects, fixes, and verification, including the cask compatibility issue missed by the initial eight-test/syntax-only check. Earlier previews remain historical artifacts; regenerate them with the updated packager.

Packaging support does not clear the outstanding app acceptance work or the pending Swift Accessibility diagnosis. No Homebrew install, upgrade, or uninstall has been validated yet.

## Sources

- [Homebrew: creating and maintaining a tap](https://docs.brew.sh/How-to-Create-and-Maintain-a-Tap)
- [Homebrew Cask Cookbook](https://docs.brew.sh/Cask-Cookbook)
- [Homebrew acceptable casks](https://docs.brew.sh/Acceptable-Casks)
- [Apple: signing with Developer ID](https://developer.apple.com/developer-id/)
- [Apple: notarizing macOS software](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
