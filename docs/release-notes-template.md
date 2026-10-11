# nashclip VERSION

Use this template for a draft release. Replace uppercase placeholders and remove instructions before publication. Describe only verified behavior.

## Highlights

- USER-VISIBLE CHANGE

## Install

Native Swift app for macOS MINIMUM_OR_LATER, built for ARCHITECTURE. Tested on TESTED_MACOS_VERSIONS.

```sh
brew install --cask OWNER/nashclip/nashclip
```

Alternatively, download EXACT_ASSET_NAME from this release, verify its checksum against `SHA256SUMS`, extract it, and move `nashclip.app` to Applications. Publish the tap before presenting its command as available. Java implementations remain available from source.

For automatic paste, allow **nashclip** under **System Settings → Privacy & Security → Accessibility**. This is separate from permission previously granted to Java. Quit older copies before starting the installed app.

## Changes and compatibility

- CHANGES
- MIGRATION_OR_DOWNGRADE_CONSTRAINTS

## Privacy and known limitations

History is saved as readable local text. Pause or quit before copying secrets. Uninstall preserves history/settings, including legacy backups. KNOWN_ISSUES_AND_WORKAROUNDS

## Verification

- Source tag/commit: TAG_AND_COMMIT
- Toolchain and CPU architecture: BUILD_DETAILS
- Automated tests: RUN_AND_REPORT
- Manual acceptance: WHAT_WAS_TESTED
- Developer ID signature, notarization, and Gatekeeper: VERIFIED_RESULTS
- Clean installation/upgrade/uninstall: VERIFIED_RESULTS

## License

Copyright 2026 Avinash Kumar. Apache License 2.0. `LICENSE` and `NOTICE` are included in the archive and app bundle.
