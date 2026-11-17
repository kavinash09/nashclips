# Changelog

User-visible changes are recorded here. Source releases and signed binary releases are separate distribution channels.

## Unreleased

No additional changes recorded.

## 1.0.1 — 2026-11-17

### Source release

- Publish a versioned source archive and SHA-256 checksum through GitHub Releases.
- Provide a Homebrew formula that compiles the native Swift app locally, without a paid Apple Developer membership.
- Set the Swift app version to 1.0.1 and increment its build number to 2.
- Preserve Apache 2.0 attribution and local clipboard history during Homebrew upgrades.

The following features and tooling are included from the existing development baseline; they were not newly implemented for 1.0.1.

### Features

- Text clipboard history in Java Swing, JavaFX, and native Swift implementations.
- Responsive masonry tiles with explicit Use, delete, and up to five persisted clip pins.
- Sliding edge drawer, menu bar controls, Control + Option + V, dragging, resizing, and light/dark themes.
- Configurable bounded history, pause controls, size rejection notices, and shared local settings.
- Exact-window checks before attempted automatic paste.
- Migration from the original mCopier data directory to `~/.nashclip`.
- Remembered drawer position in Swift and JavaFX; Clear History preserving pins in Swift.

### Development and distribution

- Automated per-variant tests, cross-version storage/locking checks, coverage policy, and mutation checks.
- Swift ZIP and Homebrew cask generation, including preview mode and release signature checks.
- Installation, contribution, security, release, architecture, and testing guides.
- Apache 2.0 license and Avinash Kumar attribution retained inside newly built Swift app bundles.

### Packaging fixes

- Updated minimum-macOS cask syntax for Homebrew 7 and added real Homebrew DSL validation tooling.
- Check expected signing team, embedded license/notice, executable deployment target, runtime dependencies, and release URL/filename agreement.
- Build fresh Swift bundles and preserve the previous build on compiler failure.
- Stage complete package outputs before making the output directory available; reject unsafe output paths and bundle symlinks.
- Check the signing identity and hardened runtime of every CPU architecture in universal binaries.
- Validate complete cask installation metadata and reject altered URLs, platform requirements, app paths, and destructive uninstall/zap definitions in Homebrew regression checks.
- Serialize Swift builds and retain recoverable backups when bundle replacement and restoration fail.

### Known limitations

- Text only; no search, sync, image history, or automatic launch at login.
- Plaintext local history without expiration or secret detection.
- Real paste, permissions, display changes, sustained profiling, clean installation, and release/runtime acceptance require manual verification.
- Java installers and a signed, notarized public Swift release are pending. The current checkout has no GitHub CI workflow.

See the dated [release verdict](docs/release-verdict.md) and [project memory](docs/project-memory.md) for evidence and outstanding work.
