# Preparing and publishing a release

This is the maintainer checklist for **Avinash Kumar**. The initial Homebrew target is the native Swift app. Java variants remain source builds. Repository preparation is complete only after the GitHub-side settings below are configured; production approval also requires the release tests.

## One-time repository setup

- [ ] Create the public project repository under the intended GitHub owner. Set its description and default branch.
- [ ] Initialize/version the local source if needed, review the files being staged, and push it to that repository. Do not assume this workspace already has a Git remote.
- [ ] Review [LICENSE](LICENSE) and [NOTICE](NOTICE), keeping Apache 2.0 and Avinash Kumar's attribution.
- [ ] Enable Issues, Actions, private vulnerability reporting, and available secret scanning/push protection in GitHub settings.
- [ ] Run the new CI workflow on GitHub. After it passes, require its tooling and macOS checks for merges using branch protection/rulesets.
- [ ] Review dependency update pull requests before merging. Dependabot is configured for GitHub Actions and the JavaFX Maven project.
- [ ] Create a separate public `homebrew-nashclip` tap repository under the intended owner.
- [ ] Replace `OWNER` and `PROJECT` in published installation/release instructions with real repository names. Documentation examples intentionally use placeholders until those are known.
- [ ] Obtain a Developer ID Application certificate and configure Apple's notarization credentials locally. The CI workflow needs no signing secrets and does not publish releases.

The workspace contains local logs, IDE settings, and development integration files that are ignored. Inspect the complete staged file list before the initial push. `.gitignore` does not remove files already tracked by Git.

## For each release

### Scope and version

- [ ] Identify the source commit, release version, CPU architecture, minimum macOS version, and tested runtime/toolchain.
- [ ] Update `CFBundleShortVersionString` and increment `CFBundleVersion` in `nashclip-swift/Info.plist` before building.
- [ ] Move the relevant Unreleased changes into a dated changelog entry. Describe migrations, known limitations, and supported/tested combinations accurately.
- [ ] Use an immutable release tag matching the bundle version, for example `v1.0`. Do not replace a published asset with different bytes under the same version.

### Verify the candidate

- [ ] Run Python tooling tests and all application suites on the release source.
- [ ] Run the coverage gates and mutation checks described in [testing](docs/testing.md). Record core and overall coverage separately.
- [ ] Complete manual acceptance with disposable data: capture, Use into the right window, permission denial/grant/revocation, shortcut conflicts, menu bar, drawer, resize, themes, pins, migration, quit, and display/Spaces behavior.
- [ ] Perform sustained resource profiling and review outstanding items in the [release verdict](docs/release-verdict.md).
- [ ] Check the actual runtimes and dependencies selected for the release. Passing old tests is not a current maintenance review.

### Sign, package, and publish

Follow [docs/homebrew.md](docs/homebrew.md) for exact commands.

- [ ] Build the candidate with embedded `LICENSE` and `NOTICE` resources.
- [ ] Apply Developer ID signing with hardened runtime, submit for notarization, inspect the accepted result, and staple the ticket.
- [ ] Verify the signed build still works. Do not rebuild it after signing.
- [ ] Generate the release ZIP, `SHA256SUMS`, and `Casks/nashclip.rb` with `scripts/package-homebrew.py` in release mode, supplying the expected Apple `--team-id` and permanent public URLs.
- [ ] Load the generated definition with `brew ruby scripts/check-homebrew-cask.rb PACKAGE_DIRECTORY`. This validates the cask DSL, checksum, URLs, platform, app path, and quit-only uninstall. Run `python3 scripts/test-homebrew-cask.py PACKAGE_DIRECTORY` to verify rejection of altered definitions. Neither command installs the app.
- [ ] Verify the generated URL, version, architecture, checksum, and bundle identity.
- [ ] Create a draft GitHub release for the exact source tag. Attach the ZIP and checksum, plus notes based on [the release notes template](docs/release-notes-template.md).
- [ ] Publish the approved release, then commit the matching generated cask to the tap. Do not publish the preview archive or `.rb.example`.
- [ ] Run Homebrew style/audit checks and test installation, first launch, upgrade, and uninstall on a disposable Mac account using the real public artifact. Confirm data survives uninstall.
- [ ] Record release evidence and any remaining limitations in the project docs. Only then advertise installation as available.

A personal tap makes `brew install --cask OWNER/nashclip/nashclip` available. The shorter central `brew install --cask nashclip` requires separate acceptance into Homebrew's official repository. See [Homebrew's cask criteria](https://docs.brew.sh/Acceptable-Casks).

## CI scope

[The CI workflow](.github/workflows/ci.yml) runs Python tooling tests, macOS core tests across all three variants, native helper builds, Swift bundle builds, preview packaging, and Homebrew cask validation/rejection checks on Apple Silicon and Intel runners. It retains reports and clearly marked preview artifacts for review. Its actions are pinned to commit IDs; Dependabot can propose updates. It has read-only repository permissions and does not request signing credentials, publish releases, or update the tap.

Desktop/native acceptance and coverage gates remain explicit local release tasks. A successful CI run with skipped manual checks is not production approval. Runner labels and tools can change; review failures against [GitHub's runner documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

## If a published release is faulty

Stop recommending it, document the affected version and workaround, and publish a new fixed version. If needed, revert the tap to a previous tested release while preserving the old artifacts and incident notes. Check storage compatibility before recommending a downgrade; old builds cannot read the current version-2 pinned history format. Do not silently replace an existing ZIP or erase users' data as part of rollback.
