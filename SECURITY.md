# Security policy

## Current support status

nashclip is in development. There is no published production release or supported release series yet. Report issues against the current source with the affected commit or build information when available. No response-time commitment is established.

## Reporting a vulnerability

On the project's GitHub page, use **Security → Report a vulnerability** if private reporting is enabled. Include the affected variant, macOS version, reproduction using synthetic data, expected behavior, and impact.

If that option is unavailable, open an issue asking the maintainer for a private reporting channel **without including vulnerability details or sensitive data**. No dedicated security email address has been configured. Maintainers must enable private reporting when setting up the public repository; adding this file alone does not enable it.

Do not post actual clipboard history, passwords, tokens, personal documents, signing credentials, or exploit details publicly. For an ordinary non-security bug, use the bug report template.

## Clipboard privacy

- History is plaintext on disk in `~/.nashclip`; the legacy `~/.mcopier` directory may also contain retained data.
- Capture has no secret detection, encryption, or expiration policy. Pause or quit before copying sensitive text.
- Deleting an entry or uninstalling does not erase the system clipboard, backups, or legacy files. File deletion is not secure erasure.
- Accessibility permits control of other applications. Automatic paste requires explicit user permission for the corresponding app/process.
- Paste validates the captured window and clipboard before key delivery, but cannot prove that a particular text field accepted the paste.

See the [README](README.md#local-data-and-privacy) for controls and storage limits, and [installation](INSTALL.md#update-and-uninstall) for data removal.

## Distribution

Public Swift releases must use Developer ID signing, hardened runtime, notarization, and an exact checksum in the Homebrew cask. Do not instruct users to disable Gatekeeper. Signing keys and notarization credentials must stay outside the repository. Local preview builds are not production artifacts.

GitHub reference: [private vulnerability reporting](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-privately).
