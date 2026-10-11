# Homebrew readiness audit

**Date:** 2026-10-11. **Scope:** Swift bundle building, cask generation, distribution checks, license delivery, CI, and release documentation. This supplements the older [application release verdict](release-verdict.md); it is not another full UI or leak audit.

## Verdict

**Not ready to publish a production Homebrew release yet.** Concrete packaging defects found in this review were fixed. The remaining blockers require a real signed candidate, public hosting, hosted CI, and clean-machine acceptance. Local packaging tests cannot substitute for those steps.

## Findings and fixes

| Priority | Finding | Resolution and evidence |
| --- | --- | --- |
| P1 | The generated `depends_on macos: ">= :ventura"` passes Ruby syntax but Homebrew 7.0.8 rejects it in developer mode. | Changed to `depends_on macos: :ventura`. Reproduced the loader rejection, then loaded the corrected cask successfully through Homebrew. Added the loader check to CI. |
| P2 | Rebuilding into the existing `.app` can retain removed resources or stray local files and sign them into the next build. | Build in a fresh temporary bundle and replace the old bundle only after successful compilation/signature verification. Tested stale-file removal and retention of the last good build on a deliberate compiler failure. |
| P2 | A failure writing checksums or metadata leaves a partial release directory. | Stage all artifacts first and move the complete result into place. Injected failures at checksum, cask, and metadata writes; none left final output. |
| P2 | The release packager accepts placeholder URLs and URLs naming a different asset. | Reject placeholder/local suffixes, credentials, query strings, fragments, custom ports, and filename mismatches. URL existence and ownership still require public-release verification. |
| P2 | Minimum macOS compatibility is inferred only from the plist; executable deployment metadata and local library dependencies are not checked. | Inspect Mach-O load commands for each declared architecture, reject newer deployment targets and non-system library/search paths, and require an executable file. The inspected arm64 binary targets macOS 13.0 and uses system libraries. Actual oldest-OS execution remains untested. |
| P2 | A Developer ID signature is accepted without checking the expected signing team. | Release mode now requires `--team-id` and checks `TeamIdentifier` before notarization/Gatekeeper checks. Tests reject a mismatched team and missing hardened runtime. A real distribution-signed positive case remains pending. |
| P2 | Top-level archive licensing does not establish that the installed app retains attribution. | Validate embedded `LICENSE` and `NOTICE` against the repository copies before packaging. The build already embeds them; missing/stale resources now fail packaging. |
| P2 | Output can be placed inside the source bundle, and symlink inputs/outputs are not explicitly constrained. | Reject output within the app, existing/dangling output links, and symlinks in this project's bundle. Tested protected-path behavior. A future framework bundle needs deliberate policy changes. |
| P2 | CI uses movable Action tags. | Pin checkout, setup-python, setup-java, and upload-artifact to full commit IDs resolved from the official repositories. Dependabot remains enabled for updates. This reduces dependency drift; it is not an audit of each action's implementation. |

The app's cask still uses the exact ZIP checksum, declares architecture, installs `nashclip.app`, and requests a normal quit for upgrades/uninstall. It does not remove shared `~/.nashclip` or legacy `~/.mcopier` data. These definitions were reviewed, but actual installation/update/uninstall has not been performed.

## First-pass verification

- **33 Python tests passed:** 15 packaging, 15 runner, and 3 build-staging tests. Signing and compiler commands in unit fixtures are mocked.
- Built the real Swift sources in an isolated temporary checkout with a synthetic stale file in the previous bundle; the new bundle excluded it and retained the license. A deliberately invalid Swift file caused the next build to fail while preserving the previous executable and cleaning temporary staging.
- Generated an arm64 preview with the updated packager in `dist/homebrew-audit-verified/`. The packager checked its real binary metadata, runtime paths, license resources, and signature.
- Independently extracted the ZIP and verified its bundle signature and embedded license bytes; release mode rejected the current ad hoc build without leaving final output.
- Homebrew **7.0.8** loaded the generated definition using `scripts/check-homebrew-cask.rb`; the version and archive/cask checksum matched. It did not install, download, launch, or uninstall the app.
- Checked workflow YAML, Ruby/shell syntax, and documentation links after edits.

Old previews in `dist/homebrew-preview/` and `dist/repository-readiness-preview/` predate the corrected cask syntax. Regenerate rather than publishing those drafts. The `homebrew-audit-preview` intermediate draft also predates the syntax correction.

## Second pass: additional defects fixed

The user requested another check after the first audit. This pass found gaps in the checks themselves and in build replacement:

| Priority | Finding | Fix and evidence |
| --- | --- | --- |
| P2 | `codesign --display` defaults to the host architecture. A universal executable's other architecture was not checked for the expected signer or hardened runtime. | Inspect every architecture explicitly and verify all signatures. Tests reject a wrong team or missing runtime in the x86_64 slice even when the arm64 slice is valid. Universal signing remains a mocked test; no distribution certificate was used. |
| P2 | The Homebrew checker accepted definitions with matching token/version/checksum but the wrong app path, URL, architecture, or uninstall behavior. | Compare the loaded cask with complete package metadata and require exactly the app plus normal quit. Seven altered casks were rejected by the real Homebrew checker, with passing unchanged controls before and after. |
| P2 | Overlapping builds could move bundles into a destination another build had just created. Failed restoration was not explicitly protected from cleanup. | Serialize builds with an atomic directory lock; reject overlapping builds and preserve the previous bundle at a reported backup path if restoration cannot complete. Tests cover overlap, failed final move, failed restore, lock cleanup, and destination symlinks. |
| P3 | A non-dictionary plist raised an uncontrolled error, and a numeric plist value was accepted where the build version must be a string. | Reject malformed metadata with explicit errors and regression coverage. |

Second-pass verification: **38 Python tests passed** (17 packaging, 15 runner, 6 build fixtures). The real Homebrew regression script passed both valid controls and rejected all **7 altered casks**: app path, download URL, homepage, minimum OS, destructive uninstall, extra zap, and architecture. No altered uninstall instructions were executed. YAML/Ruby/shell syntax checks passed. The updated preview is `dist/homebrew-recheck-preview/`; regenerate old previews because the stricter checker requires additional metadata.

The real Swift app was then rebuilt successfully, packaged into `dist/homebrew-recheck-final/`, and checked again: complete cask validation passed, embedded license/notice matched, extracted signature verification passed for all architectures present (arm64), and the build lock was released. This is still an ad hoc preview, not a signed distribution candidate.

## Still required before publication

1. **Distribution identity:** choose the actual GitHub owner/project/tap and Apple signing identity. No public release or tap was created or verified during this work.
2. **Signed positive test:** Developer ID + expected team + hardened runtime + accepted notarization + stapled ticket + Gatekeeper acceptance on the exact final artifact. The current app remains ad hoc signed.
3. **Public artifact checks:** upload the exact candidate ZIP/checksum, validate the real URLs, and run Homebrew style and online audit against the published tap.
4. **Installation and upgrade acceptance:** test a downloaded/quarantined release, upgrade from a previous candidate, quit/relaunch, and uninstall with preserved history, in a disposable account. Validate both CPU architectures before advertising both, and run on the actual supported macOS versions. The generated cask covers one architecture unless given a universal build; publishing two separate single-architecture casks under the same token would replace one with the other. A combined architecture-aware cask is a future step if both are shipped separately.
5. **App acceptance:** verify Swift Accessibility behavior and real paste, including multiple windows/Spaces, on the installed signed app. The earlier Swift permission diagnosis remains open. Preserve the prior user-confirmed Java paste success as separate evidence.
6. **Hosted CI and profiling:** execute the workflow on GitHub and complete the application's outstanding manual/runtime/resource checks. This review did not rerun the whole application suite or claim leak freedom.

Use [RELEASING.md](../RELEASING.md) for the publishing checklist and [Homebrew instructions](homebrew.md) for commands.

## Primary references

- [Homebrew Cask Cookbook](https://docs.brew.sh/Cask-Cookbook): current minimum-OS syntax, cask artifacts, quit and zap behavior.
- [Homebrew acceptable casks](https://docs.brew.sh/Acceptable-Casks): official inclusion and Gatekeeper requirements.
- [GitHub workflow security guidance](https://docs.github.com/en/code-security/tutorials/secure-your-organization/protect-against-threats): pinning actions to full commit IDs.
