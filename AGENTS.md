# nashclip: agent handoff

Last updated: **2026-11-17** (Asia/Kolkata). Applies to this project and its three implementations.

## Read first

1. [Project memory](docs/project-memory.md): development history, user preferences, decisions, verified results, and current debugging state.
2. [Architecture walkthrough](docs/architecture-walkthrough.md): directory map, responsibilities, execution paths, and where to change each feature.
3. [Testing guide](docs/testing.md): commands, isolation, coverage policy, and manual acceptance procedures.
4. [Release verdict](docs/release-verdict.md): dated 2026-10-02; its production-release blockers remain open unless newer evidence clears them.

These files are a handoff, not live system state. Recheck running processes, OS permissions, source changes, and tool versions before acting. Earlier dated audits describe their own runs.

## Current work to resume

2026-11-17 source release 1.0.1: published GitHub Release `v1.0.1` with an exact
source archive and SHA256SUMS; tag source commit is `026fe06`. Swift bundle
version is 1.0.1, build 2. Fresh archive build and 38 Python tooling tests passed;
public asset download/checksum and Homebrew formula style passed. Formula now
points to the release asset and tests bundle version. Formula push and real
preview-to-1.0.1 upgrade remain pending in this checkpoint; see project memory
for final completion. This is source distribution, with the existing app
acceptance and signed binary gaps still open.


2026-11-17: user requested Homebrew publication, confirmed no Apple Developer
membership, and requested use of the existing Git client. Added a personal-tap
source formula at `Formula/nashclip.rb` and installation guide in
`homebrew/README.md`. It pins public source commit `e4da8e8`, builds locally,
and installs formula version `1.0-preview.1` with a `nashclip` launch command.
The existing `kavinash09/nashclips` GitHub repository doubles as the tap through
an explicit URL. Real local Homebrew source installation, bundle/signature
tests, and formula style checks passed on Apple Silicon. Formula/docs commit
`317a35d` was pushed to the existing GitHub repository. A fresh public tap clone
matched it, a second source installation succeeded, and `brew test` passed
again. The installed tap uses the correct public remote. See project memory
for installation commands. No real app launch/paste, permission change,
Developer ID signing, or production approval is implied. The repository now
has Git on `main` with the public GitHub origin; October's no-Git notes are historical.

Second Homebrew review on 2026-10-11: fixed per-architecture signer/runtime inspection, expanded cask checks to URLs/platform/artifacts/quit-only uninstall, serialized Swift builds, protected rollback backups, and validated plist shape/build-version types. Added `scripts/test-homebrew-cask.py` to CI. Verification: 38 Python tests; 7 altered casks rejected by real Homebrew with 2 passing controls; real Swift rebuild and final preview/extracted signature validated. Latest preview: `dist/homebrew-recheck-final/`. The [audit](docs/homebrew-audit.md#second-pass-additional-defects-fixed) records details. Public release/signing/installation prerequisites remain unchanged.

The subsequent Homebrew scrutiny found and fixed packaging defects; read [Homebrew audit](docs/homebrew-audit.md) first for this work. Homebrew 7 rejected the old minimum-OS string syntax in developer mode; the generated cask now uses `:ventura` and CI runs a real CaskLoader check. Packaging now verifies Mach-O deployment/library paths, embedded licensing, expected `--team-id`, and URL/filename agreement; complete outputs are staged before publication to the local directory. Swift builds start with a fresh bundle and preserve the previous build on compilation failure. Actions use immutable commit pins. Local checks: 33 Python tests, real isolated clean/failing build, corrected cask loaded by Homebrew 7.0.8, preview checksum/extracted signature, and real ad hoc release rejection. No signed positive release or installation has been tested; publication remains blocked on the external prerequisites in the audit. Regenerate older previews.

Repository preparation followed the Homebrew request on 2026-10-11. Added `INSTALL.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, `RELEASING.md`, a release-notes template, GitHub issue/PR templates, Dependabot, and a CI workflow for Python tooling plus macOS headless/build/preview checks. README navigation, ignore rules, editor/line-ending conventions, and author/license build metadata were updated. Swift now embeds `LICENSE`/`NOTICE` before signing. The workflow has not run on GitHub, and no repository/tap/release was created or published. See project memory for local verification; no new production approval is implied.

Homebrew tooling and instructions were added earlier on 2026-10-11: [Homebrew guide](docs/homebrew.md), `scripts/package-homebrew.py`, and `tests/test_homebrew_package.py`. An arm64 preview is in ignored `dist/homebrew-preview/`. Eight packaging tests passed; archive contents/checksum, Ruby syntax, extracted signature, and rejection of the real ad hoc build in release mode were checked. Nothing was published or installed through Homebrew. GitHub owner/repositories and Apple Developer signing availability were requested and remain unanswered. Release mode requires a Developer ID/hardened-runtime build with a valid stapled ticket and Gatekeeper approval. These packaging checks do not clear app acceptance blockers.

The previous Swift paste diagnosis remains open:

The user launched the **Swift** app and reported that Use does not paste into the open destination window.

- On 2026-10-11, System Settings → Privacy & Security → Accessibility showed **`nashclip` OFF** and **`java` ON**. Java's authorization does not cover the Swift app.
- This is a confirmed blocker for Swift automatic paste. Success after enabling it has **not** been verified.
- A request to enable `nashclip` Accessibility was presented, but the user has **not answered it**. Their next request was to write this memory. Do not interpret the memory request or the earlier Java permission approval as approval for Swift.
- The Swift app was relaunched with stdout/stderr in `test-results/swift-runtime/`; the process was present and those logs were empty at the last check. Do not reuse an old PID.
- No paste implementation was changed during this diagnosis. `PasteCoordinator.paste` checks for a captured window before checking Accessibility trust; this can mask the permission explanation. It is a noted UX issue, not an implemented fix.
- Next: resolve the pending permission decision, recheck the switch/process, then verify Use with disposable text in a focused text field. If it still fails, record the footer message and distinguish clipboard copy, target capture, restoration, verification, and key delivery.

## Project identity and collaboration

- Product: **nashclip**; originally **mCopier/mcopier**. Workspace remains `/Users/avinash/avinash/mcopier`.
- Author: **Avinash Kumar**. License: **Apache 2.0**; preserve `LICENSE` and `NOTICE`.
- The user began as a Java learner comfortable with classes, collections, and exceptions. They want to understand separation of responsibilities and integration.
- For teaching requests: one small step at a time, progressive hints, clear success criteria, and at most three questions at once. Let them attempt the task; end a lesson with a recap and one understanding check.
- Later requests explicitly authorized hands-on implementation, fixes, audits, and tests. Implement when asked; do not revert every coding request to a lesson or ask for permission already provided.
- Treat corrections as requests to correct the work. Keep confirmed defects, user-reported success, architectural risks, and untested scenarios distinct.
- The earlier Java PID-only window-targeting defect was fixed. The user confirmed working paste on 2026-10-02. Do not resurrect it as a confirmed current bug. The current Swift permission problem is a separate observation.

## Source and execution

| Variant | Production source | Main entry | Run from project root |
| --- | --- | --- | --- |
| Swing | `java-swing/src/` (default package) | `Main` → `NashClip` | `sh java-swing/run.sh` |
| JavaFX | `java-fx/src/main/java/nashclip/` | `Launcher` → `NashClipApp` | `sh java-fx/run.sh` |
| Swift | `nashclip-swift/Sources/` | `NashClipMain` → `AppController` | `sh nashclip-swift/run.sh` |

Use the scripts to establish the right working directory and build native helpers. Java's focus library is resolved relative to the variant directory. Run only one normal app version against the shared data directory at a time. Do not terminate an existing app just to free the lock without checking what is running.

The last inspected workspace had no `.git` directory. Verify repository status before using Git; do not assume a branch or remote. `.github/modernize/` exists but does not establish a release pipeline. Do not modify unrelated hooks or logs for app tasks.

## Behavior and compatibility to preserve

- Dedicated **Use** button copies the full accepted text, then attempts paste into the verified original window. A failed verification leaves manual paste available. Do not weaken exact-window or clipboard-change checks to make a test pass.
- Tile widths are constrained and heights follow content. Masonry chooses the shortest of up to three columns; a short B lets D fit beneath it.
- Drawer opens after a hover delay or via arrow/tray/Control+Option+V. Hover-out keeps it open. Outside click dismisses until an inside click latches it; drawer pin keeps it open. Arrow remains available to dismiss.
- Header pin and clip pins are different. At most **five persisted clip pins**, in addition to the ordinary-history limit and within the shared text budget.
- Shared history/settings/size data live in `~/.nashclip`; migrate `~/.mcopier` once while preserving legacy files. Do not erase or rewrite real user history for diagnostics.
- Keep storage formats, bounds, UTF-16 identity, pause/own-copy behavior, and POSIX lock interoperability aligned across versions.
- Swift and JavaFX persist position. Swing currently does not. Swift alone has Clear History; it preserves pinned clips. Do not claim full feature parity.
- Light/dark themes preserve the yellow/cream design; active pins have a darker shade. Size, defaults, and notices are user-configurable.
- Text only: images, search, sync, launch at login, and distribution installers are not implemented.

## Verification

```sh
python3 scripts/test.py --list
python3 scripts/test.py --variant swift --only ModelFeatureTests
python3 scripts/test.py --headless
python3 scripts/setup-coverage.py
python3 scripts/test.py --check-coverage --report-dir test-results/my-run
python3 scripts/mutation-checks.py --report-dir test-results/my-mutations
```

- Use focused tests for a focused fix; run all variants/cross-version checks for shared-format or policy changes. See the testing guide for other program names and prerequisites.
- Tests use temporary homes, private/fake clipboards, and fake Java paste effects. Keep those boundaries. Real paste acceptance should use a deliberately prepared disposable destination, not an arbitrary active app or message composer.
- The 2026-10-02 full recheck passed **146 checks/groups + 9 coverage gates**, with **16/16 seeded mutations detected** and **27 manual entries skipped by the runner**.
- Core line coverage was Swing **95.10%**, FX **92.22%**, Swift **95.50%**. Overall Java/Swift production-source coverage was **63.76% / 76.87% / 62.41%**. Native Objective-C helper source is not in that denominator.
- Policy: 90% core lines, 85% Java core branches/Swift core regions, plus explicit overall floors. Do not equate passing this gate with 90% whole-app coverage or release approval.
- Build steps, fixture creation, grouped tests, and coverage gates are separate categories. Consult `summary.json` rather than presenting the aggregate as a count of independent tests.
- Memory/resource cycles do not establish leak freedom. Keep native paste, sustained profiling, clean installs, runtime validation, and signing gaps visible.

## Keep this handoff useful

After a meaningful change, update the dated current state and relevant walkthrough/verification section. Record what was changed, what was actually tested, what the user confirmed, and what remains pending. Preserve historical evidence without describing old results as a fresh run. Never copy clipboard contents, secrets, or unrelated screen text into project memory.
