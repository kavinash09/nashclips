# nashclip Homebrew tap

The formula is in `Formula/nashclip.rb` at the root of the existing
`kavinash09/nashclips` repository. This repository also acts as a personal tap;
no separate GitHub repository is needed. Publication status is recorded in
`docs/homebrew.md`; local files alone do not mean that the tap is live.

## Install version 1.0.1

On a Mac with Homebrew and compatible Apple Command Line Tools installed:

```sh
brew tap kavinash09/nashclip https://github.com/kavinash09/nashclips.git
brew install --formula kavinash09/nashclip/nashclip
nashclip
```

If development tools are missing, run `xcode-select --install` first and finish
the installation before running Homebrew. The formula builds the native Swift
app for your Mac. It needs no Java or paid Apple Developer membership. macOS 13
is the app deployment target; Homebrew and the installed compiler have their
own supported OS requirements. Only Apple Silicon has been validated locally.

This is a source release, not a signed/notarized binary release. The formula
downloads the exact source archive attached to GitHub Release `v1.0.1`, pinned
by its SHA-256 checksum. It is a personal-tap formula, not a submission to `homebrew/core` or
`homebrew/cask`.

The app lives inside Homebrew's installation directory, not `/Applications`.
Run `nashclip` to open it. Quit other variants first. For automatic paste, enable
nashclip in System Settings → Privacy & Security → Accessibility. Homebrew does
not grant that permission. Updates may require renewing it.

History is readable text in `~/.nashclip`, shared with the Java variants. Pause
or quit before copying secrets. No sync or encryption is provided. Real paste,
multi-display behavior, long-running resource profiling, and clean-machine
acceptance remain pending; see the main project's release verdict.

## Update or remove

Quit nashclip first, then run:

```sh
brew update
brew upgrade --formula kavinash09/nashclip/nashclip
# To remove instead:
brew uninstall --formula kavinash09/nashclip/nashclip
```

Uninstalling preserves your history and settings.

## Maintain

For each update, bump the app version and build number, tag the source, and
publish an archive plus SHA256SUMS on GitHub Releases. Then update the release
asset URL, SHA-256, and version in `Formula/nashclip.rb`. Test a real source
installation or upgrade and
`brew test kavinash09/nashclip/nashclip` before pushing. Do not add bottles of
the ad hoc app: this route intentionally builds locally.

Copyright 2026 Avinash Kumar. Licensed under Apache-2.0; see LICENSE and NOTICE.
