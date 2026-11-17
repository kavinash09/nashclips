# nashclip project memory

**Updated:** 2026-11-17, Asia/Kolkata.
**Purpose:** durable handoff from the conversation and inspected source. Read [AGENTS.md](../AGENTS.md) for working guidance and the [architecture walkthrough](architecture-walkthrough.md) for implementation paths.

## 1. Current state in one minute

**2026-11-17 Homebrew source preview:** the user requested publication and
confirmed they have no Apple Developer membership, then explicitly requested
the existing Git client. Added `Formula/nashclip.rb`, using the existing public
`kavinash09/nashclips` repository as the tap via an explicit `brew tap` URL.
It builds source commit `e4da8e80dc33c5ec1d081f8297c40c5574f08467` on the receiving
Mac, checksum-pinned, as formula version `1.0-preview.1`. It installs the app
under Homebrew's libexec with a `nashclip` launcher. No paid signing certificate,
binary cask, or Gatekeeper bypass is involved. Added `.gitignore` for build and
report outputs and documented installation in README/INSTALL/homebrew guides.

Verified locally on Apple Silicon: downloaded the real GitHub source archive,
rebuilt Swift, installed the formula through real Homebrew from a temporary
local tap, passed `brew test` (executable/resources/signature), and passed
`brew style` with no offenses. The tests did not launch the app, access real
history, or change Accessibility permission. Published formula/docs commit `317a35d` to `origin/main` using the
existing Git client. A fresh Homebrew tap cloned that public commit, matched
the formula byte-for-byte, and uses the public project URL rather than the
disposable staging repository. A second real installation from that public tap
succeeded, and `brew test kavinash09/nashclip/nashclip` passed again. The local
app is installed at `/opt/homebrew/opt/nashclip/libexec/nashclip.app`, with
`/opt/homebrew/bin/nashclip` as launcher. Removing the temporary tap with
`brew untap --force` also uninstalled its formula on this Homebrew version;
the successful public-tap installation restored it. No user history was removed.
Developer ID/cask release, clean-machine, Intel, real paste, and broader app
acceptance remain unverified.

Install on another Mac with Homebrew and compatible Command Line Tools:

```sh
brew tap kavinash09/nashclip https://github.com/kavinash09/nashclips.git
brew install --formula kavinash09/nashclip/nashclip
nashclip
```

Git now exists: branch `main`, origin `https://github.com/kavinash09/nashclips.git`.
Historical statements below saying no Git repository describe October's state.
GitHub CLI was installed while investigating publishing access, but its sign-in
was cancelled following the user's request to use Git. Do not assume CLI auth.

nashclip is a macOS text clipboard history app implemented three ways: Java Swing, JavaFX, and native SwiftUI/AppKit. The project began as a learning exercise and grew into a working drawer interface with persistence, five clip pins, themes, resizing, native hotkeys, and verified-window paste attempts.

The current interruption is **Swift Use cannot paste into the open destination**. On 2026-10-11, the Accessibility settings UI showed `nashclip` disabled and `java` enabled. The agent asked for approval to enable the Swift permission. **No approval or successful post-permission paste has been recorded.** The user requested this memory next.

The last full automated recheck was 2026-10-02. It passed its tests and coverage gates. It did not clear production distribution, full native acceptance, responsiveness, or sustained profiling. The user later corrected stale wording about Java wrong-window pasting; that defect was fixed and paste was working in their testing. Do not confuse that correction with the current Swift permission issue.

## 2. User goals and working style

- Author: Avinash Kumar. Original name mCopier; current branding nashclip. Apache License 2.0.
- Initial inspiration: Windows Win+V, with a small macOS app built primarily in Java.
- Initial Java experience: comfortable with classes, collections, and exceptions. IDE: VS Code; willing to use IntelliJ.
- The user wanted mentoring, decisions explained, small coding tasks, progressive hints, debugging from cause to fix, and reviews of code they wrote.
- They later explicitly asked the agent to implement UI refinements, native integrations, ports, persistence, auditing, and tests. Respond according to the current request: explain when teaching; implement when asked to change the app.
- They care about polished desktop behavior: small footprint, smooth movement, restrained pins/icons, yellow/cream colors, correct focus, and no accidental dismissals.
- They requested rigorous testing and a candid shipping verdict, then corrected claims that described a fixed feature as still broken. Use precise evidence labels.

## 3. Development history

The early stages below are ordered from the conversation; exact dates were not recorded for each step.

### Stage A — Learn Swing and create a small window

The user started with a basic Java frame, placement lower on the screen, and partial transparency. They encountered decorated-frame opacity errors, hidden heading/content, syntax errors, and history-model updates not appearing in the UI. Work focused on understanding AWT, Swing containers, models, and repaint/update behavior.

History started with `DefaultListModel<String>`, `JList`, and a scroll pane. The UI evolved into custom panels/tiles. The requested arrangement was three columns with fixed widths and variable height. A later refinement used masonry: each new tile goes into the shortest column, so D can sit below short B while A and C extend farther down.

### Stage B — Capture, copy, and automatic paste

Clipboard polling was connected to the displayed model. The user moved from clicking a tile to a dedicated Use button. Use writes the entire accepted clip to the clipboard and attempts automatic paste, with manual Command+V as fallback.

Early failures involved focus restoration and missing macOS Accessibility permission. The user explicitly authorized enabling **Java** Accessibility during that earlier debugging. That authorization did not apply to a separate native Swift app.

Multiple windows within the same application exposed the limits of identifying only the application/PID. Java later gained JNI/AX window identity: capture the exact window, freeze it for a Use operation, restore it, and compare it before sending keys. Clipboard content is also checked around the restore delay. Swift freezes its captured target and checks both text and pasteboard change count.

### Stage C — History and visual design

Delete controls were added, history became persistent under the old `~/.mcopier` folder, and settings became editable through JSON. The user requested a modern yellow/cream appearance, reduced window size, placement that obstructs less content, and closing after Use.

The UI became a drawer with a narrow translucent edge handle. Smooth opening/closing, delayed hover opening, global Control+Option+V, and a menu bar icon were added. Drawer pinning became persistent and available from the menu bar. Pin styling was refined into a small control with a darker active shade.

The user initially requested quick hover-out removal, then explicitly changed the behavior: **leaving the drawer must not close it**. Outside clicks can dismiss a hover-opened drawer until a click inside latches it open; the arrow remains the explicit dismissal control. Preserve the later behavior.

### Stage D — Movement, resizing, and layout

The handle and header became drag surfaces. Releasing near an edge docks left or right; the arrow belongs on the outer side. Resizing uses the inner horizontal edge and top/bottom edges. Current size and user-saved defaults persist, with a reset-to-default action. JavaFX and Swift remember placement across restarts; Swing still lacks persisted placement.

Modal settings interactions had triggered an AWT Accessibility `IllegalComponentStateException` on Save/Cancel. The audit history and drawer tests document the subsequent lifecycle/modal work. Avoid treating the old stack trace as a freshly reproduced defect.

### Stage E — Separate implementations and branding

The original app was organized into `java-swing` with clearer responsibilities and a README. The user then requested equivalent JavaFX and Swift implementations. The native implementation currently lives in **`nashclip-swift`**, not the originally requested `mcopier-swift` name.

Light/dark variants, project motivation, local run instructions, limitations, author attribution, and Apache 2.0 licensing were documented. Branding changed from mCopier to nashclip. The checkout path remains `mcopier`; live data moved to `~/.nashclip` with a one-time legacy copy that preserves `~/.mcopier`.

### Stage F — Bounds, privacy, pinning, and lifecycle audit

Auditing added/fixed bounded retention, private file permissions, atomic replacement, cross-version locking, pause/resume, Hide/Quit controls, timer/process/native-reference cleanup, cancellation, and shutdown behavior.

Oversized copied text produces a dismissible/fading size notice without becoming a tile. Notice duration is configurable, including manual dismissal. Up to five persisted clip pins were added, separate from the drawer pin. Ordinary-history eviction preserves those pins while observing a shared text budget.

The audit fixed Unicode interoperability, including exact NFC/NFD identity, whitespace differences, escaped CRLF, and Java properties encoding. See [production-audit.md](production-audit.md) for the detailed defect/fix ledger.

### Stage G — Test suites, scrutiny, and release recheck (2026-10-01–02)

The user requested suites for every version and asked for roughly 85–90% or a reasonable amount of meaningful coverage. Work added a common runner, variant suites, desktop lifecycle checks, cross-version storage/locking fixtures, measured coverage, gates, and mutation checks.

Test scrutiny found assertions that could pass without proving their stated behavior:

- Use tests began with an already-hidden drawer; they now open it before asserting Use hides it.
- Click-inside latch tests checked too early; they now allow an incorrect close animation enough time to finish before asserting visibility.
- Swift save/load tests masked a broken in-memory history limit because loading trimmed again; a direct in-memory assertion was added.
- A silent exit or a named failure with process exit 0 could mislead the runner; result parsing and propagation were strengthened.
- Swift invalid position saves could overwrite valid saved geometry. Shared load/save validation fixed the reproduced failure.
- Swift named output is flushed so failures remain visible; failure completion unwinds test cleanup.

The first mutation pass exposed a surviving Swift ordinary-limit fault. After strengthening the assertion, all 16 selected faults were detected. The production paste failure text was also corrected from “regain platform” to “regain focus.”

A fresh release review rebuilt all variants. Swift's local signature passed integrity checks, but the ad hoc app was rejected by the unsandboxed Gatekeeper assessment. JavaFX Maven packaging succeeded after missing build plugins were downloaded, but the JAR lacked a launch manifest entry and native helpers. These were recorded as release packaging gaps.

### Stage H — Correct the interpretation of working paste (2026-10-02)

The user objected to wording implying Java still pasted into the wrong window and reported that it was working. The audit, release verdict, and scrutiny report were corrected:

- The old PID-only targeting defect is fixed.
- User-observed working paste is valid evidence for the behavior they tested.
- The broader unrecorded OS/app scenario matrix remains a validation gap.
- Do not call an untested scenario a confirmed current bug.

### Stage I — Swift launch and current paste diagnosis (2026-10-11)

1. The user asked to run Swift. `sh nashclip-swift/run.sh` rebuilt the bundle.
2. Sandboxed `open` reported `kLSNoExecutableErr` (-10827). The executable existed and was executable. An approved unsandboxed `open` succeeded; the process was verified. This did not establish an app build defect.
3. The user reported Swift was not working as expected. A subsequent process check found it absent; no matching crash report was found. The cause of that absence was not established.
4. The app was relaunched with diagnostic stdout/stderr under `test-results/swift-runtime/`. The process stayed present at the check and both logs were empty.
5. The user clarified that it could not paste into the open window. Source review traced `AppController.use` through copy, drawer hide, and `PasteCoordinator.paste`.
6. UI inspection showed the drawer and existing history. No clipboard contents were copied into this memory.
7. The Accessibility settings UI showed **nashclip OFF**, **java ON**. This directly explains why Swift lacks permission to inspect/control the destination and synthesize paste. It does not prove that enabling permission will resolve every possible failure.
8. The agent asked whether it could enable nashclip Accessibility, explaining the scope of access. **The user has not approved or declined. The toggle was not changed.**
9. A misleading error path was identified: missing captured-window validation precedes the trust check, so permission denial can surface as window-verification failure. No code change was made for it.
10. The user then requested this memory and `AGENTS.md`. Runtime permission work is pending; writing the handoff does not grant that permission.

## 4. Feature contract and limits

| Area | Current behavior |
| --- | --- |
| Capture | Text polling about once per second; baseline at startup avoids importing the existing clipboard automatically. |
| Pause | Session pause; resume skips paused content; restart returns to live capture. |
| Use | Explicit button, full accepted text, attempted exact-window restoration/paste, manual fallback. |
| Layout | Up to three responsive masonry columns with variable tile heights. |
| Drawer | Delayed hover open; hover-out stays open; outside-click/latch/pin behavior; smooth movement. |
| Drag/resize | Header/handle dragging, left/right docking, inner-edge and top/bottom resizing. |
| Pins | Five persisted clip pins; separate persisted drawer pin. |
| Themes | Light and dark with yellow/cream styling and distinct active pins. |
| Clear | Swift clears ordinary history and keeps pins; Java versions delete individual tiles. |
| Position | Persisted in JavaFX and Swift, not Swing. |
| Ordinary history | Default 40; valid range 1–500. |
| Clip text | Maximum 65,536 UTF-16 units; larger new clips rejected. |
| Total retained text | Maximum 1,048,576 UTF-16 units including pins. |
| Preview | Up to 1,000 UTF-16 units; avoids splitting a surrogate pair. |
| Files | History read bound 16 MiB; settings/geometry reads bounded to 64 KiB. |
| Notice | `sizeNoticeSeconds`: 2–5, default 4; 0 means manual dismissal. |
| Size | Factory 760×340; configured bounds 500×260 through 4096×2160, with screen-aware drawer positioning. |

The stored-text budget does not bound complete process RSS or allocations made before a clipboard value is rejected.

## 5. Verified evidence and its scope

The **2026-10-02** release recheck is the latest full automated run recorded here. The October 11 launch was not a rerun of the whole suite.

| Variant | Core lines | Overall Java/Swift production lines | Core additional metric |
| --- | --- | --- | --- |
| Swing | 95.10% | 63.76% | 94.39% branches |
| JavaFX | 92.22% | 76.87% | 89.29% branches |
| Swift | 95.50% | 62.41% | 92.53% regions |

- 146 automated checks/groups and 9 coverage gates passed; zero failures.
- Twelve suite build checks and six fixture-generation steps were counted separately. Aggregate JSON total: 173 passed, including builds/fixtures/gates.
- Sixteen seeded mutations detected; eight unchanged controls passed.
- Fifteen runner self-tests are represented by one grouped result.
- Twenty-seven manual entries remain skipped by the automated runner. User reports are separate evidence; do not mark the entire matrix complete from a general success report.
- Java uses JaCoCo 0.8.15. Swift uses LLVM source coverage. Java branches and Swift regions are different counters.
- All production `.java`/`.swift` files remain in overall coverage, including unexecuted startup/UI paths. Native Objective-C helper implementations are not instrumented in this denominator.
- Core scope is explicitly listed in `scripts/coverage_tools.py`; do not claim its percentage covers the full app.

Local evidence is under `test-results/release-recheck/`, `test-results/release-mutations/`, and the earlier `test-results/coverage-audit/`. Reports are ignored/generated and may not exist in a fresh checkout. Details: [test scrutiny](test-scrutiny.md), [release verdict](release-verdict.md), [testing guide](testing.md).

## 6. Remaining work and evidence needed

### Homebrew packaging added on 2026-10-11

The user asked whether nashclip could be shipped through Homebrew. The Swift app is the first distribution target, using a cask in a personal tap. The Java variants keep their existing source-run workflows.

- Added `scripts/package-homebrew.py`: snapshots a built app, reads its version and architecture, packages the app with `LICENSE`/`NOTICE`, calculates SHA-256, and generates the cask. It refuses to overwrite output directories.
- Release mode checks the sealed bundle, Developer ID/hardened runtime, stapled notarization ticket, and Gatekeeper assessment. Preview mode produces an explicitly marked archive and `.rb.example` draft.
- Added [publishing instructions](homebrew.md), a README entry, and eight unit tests in `tests/test_homebrew_package.py`. Unit signing checks use mocked commands.
- Actual local verification: all eight tests passed; created `dist/homebrew-preview/nashclip-1.0-macos-arm64-preview.zip`; inspected archive entries, matched checksum, parsed cask Ruby, extracted and verified the bundle signature, and confirmed release mode rejects the real ad hoc build without emitting release output.
- No application source, permissions, or real history changed. No app acceptance suite was rerun. No Homebrew audit/install/upgrade/uninstall, signing, notarization submission, tap creation, or upload occurred.
- Asked for GitHub owner/repositories and Apple Developer signing availability; answers remain pending. The workspace still has no `.git` directory. Preview outputs are ignored under `dist/`.

This establishes local packaging support, not a published or production-approved release. The earlier Swift permission decision remains pending independently.

### Repository preparation on 2026-10-11

The user then asked to make the repository ready with licensing, installation documentation, and supporting files.

- Preserved the Apache 2.0 license and Avinash Kumar attribution. Added copyright metadata to the Swift plist and license/author metadata to the JavaFX POM. Swift builds now include the existing `LICENSE` and `NOTICE` in `Contents/Resources` before signing, preserving attribution after app installation.
- Added root installation, contribution, security, changelog, and release guides; added a release-notes template and README navigation. Instructions explicitly identify the unpublished tap and unsigned local build status.
- Added GitHub bug/feature forms, a PR template, and weekly Dependabot configuration for Actions and Maven.
- Added read-only CI configuration for Python tooling, all-variant headless/core/cross-version checks, native helper builds, Swift builds, and preview packaging. It targets Apple Silicon and Intel macOS runners, stores short-lived reports/previews, and has no signing credentials or publishing steps. Hosted execution remains unverified.
- Added editor/line-ending defaults and ignored local IDE settings, development integration files, logs, credentials, and build outputs. A temporary Git index check confirmed 133 eligible source/documentation files and exclusion of generated output/local logs. The real workspace was not initialized as a Git repository.

Local verification:

- **23 Python tooling tests passed** (8 packaging + 15 runner).
- Headless report `test-results/repository-readiness/`: **102 test checks/groups passed**, two Java `AuditChecks` failed because the sandbox blocked `sysctl` process enumeration, and three desktop/native groups were skipped. Six builds and six fixture steps passed; 27 manual entries were skipped.
- Both blocked `AuditChecks` passed when individually rerun outside the sandbox with temporary data, recorded in `test-results/repository-readiness-swing-process/` and `test-results/repository-readiness-fx-process/`. The original report remains unchanged; it is not a zero-failure report.
- Both native Java helpers and the Swift app rebuilt successfully. Checked YAML syntax for all five added YAML files, shell syntax, Maven/plist metadata, and local Markdown links.
- Generated `dist/repository-readiness-preview/`; checked the ZIP checksum, both archive-level and app-embedded license/notice bytes, copyright metadata, extracted bundle signature, and cask Ruby syntax.

No desktop acceptance, coverage rerun, hosted CI run, public upload, Homebrew install, distribution signing, notarization, or Accessibility change took place. GitHub account/repository details and signing setup remain external prerequisites in [RELEASING.md](../RELEASING.md).

### Homebrew scrutiny follow-up on 2026-10-11

The user's next message requested scrutiny for Homebrew but ended at “check for.” A clarification was requested while a broad packaging audit proceeded; no additional scope response was received during the audit.

The [Homebrew audit](homebrew-audit.md) records concrete defects and fixes: obsolete minimum-OS cask syntax rejected by Homebrew's loader, stale files retained by in-place builds, partial output after write failures, placeholder/mismatched URLs, missing binary deployment/dependency checks, no expected-signer identity check, unverified installed license resources, path/symlink gaps, and mutable Action tags.

Updates: fresh staged Swift builds with failure rollback; stricter release packaging including required `--team-id`; updated cask minimum syntax; `scripts/check-homebrew-cask.rb` and CI integration; Action commit pins resolved from official GitHub repositories; additional packaging and build regressions; updated release commands/docs. No app behavior or shared storage code changed.

Verification: **33 Python tests passed** (15 packaging, 15 runner, 3 build fixtures); a real isolated Swift build excluded a synthetic stale file and survived a deliberate failed rebuild with the prior executable intact; the corrected preview loaded in Homebrew **7.0.8** and matched its checksum/version; extracted archive signature and embedded license bytes verified; release mode rejected the real ad hoc build without final output. YAML/Ruby/shell syntax and document links passed. No fresh full application/coverage run was needed for these packaging changes. No install, upgrade, notarization, publication, or permission change occurred. Older previews remain historical and must be regenerated; `dist/homebrew-audit-verified/` is the corrected local preview.

### Second Homebrew recheck on 2026-10-11

The user requested another check and fixes. This pass found that default `codesign --display` inspects only the host CPU architecture, and that the cask checker matched token/version/checksum without validating install behavior. Release inspection now explicitly checks the expected signer and hardened runtime of every architecture. Cask validation additionally matches URLs, architecture/minimum OS, app artifact, and quit-only uninstall, rejecting extra destructive artifacts. The metadata now includes the intended cask URL and homepage.

Swift builds now use an atomic directory lock to prevent overlapping replacements. If installation fails, they restore the previous bundle; if restoration also fails, they retain and report a recovery path. A killed build may leave a stale lock; confirm no build is running before manually removing it. Plist checks now reject non-dictionaries and non-string build versions with clear errors.

Verification: **38 Python tests passed** (17 packaging, 15 runner, 6 build fixtures). The new real-Homebrew regression script rejected **7 modified definitions** (app path, download URL, homepage, minimum OS, destructive uninstall, added zap, and architecture), with unchanged controls passing before/after. The updated Swift script compiled the real app; `dist/homebrew-recheck-final/` passed the stricter cask checker, embedded-license check, and extracted all-architecture signature verification. No real installation or destructive cask action was performed. GitHub CI now invokes the Homebrew regression script, but hosted execution remains pending. No app source behavior, permission, history, or production-release status changed.

### Immediate

Resolve the **pending Swift Accessibility permission decision**, then perform a disposable-text Use test. Check the destination field, footer message, and clipboard versus paste outcome. Do not send test keystrokes into a real message composer or treat an enabled switch alone as a successful paste test.

### Implementation follow-ups

- Better permission diagnostics in Swift before the missing-window fallback.
- Bounded, ordered background persistence with snapshot coalescing and clear failure/shutdown semantics. Current UI-thread writes remain synchronous.
- Broader Swift paste testing through an injectable OS boundary; retain exact-window and clipboard checks.
- Startup/tray/dialog/quit wiring tests beyond selectively assembled controller fixtures.
- Profile transient large-clipboard reads and expensive layout; respect toolkit-specific thread constraints.

### Release validation

- Standalone Java packages and Developer ID/notarized Swift distribution, plus clean-machine installation.
- Runtime/dependency maintenance review and compatibility checks on the actual versions chosen to ship.
- Real paste, permission changes, global shortcut conflicts, tray, dialogs, VoiceOver, display changes, and Spaces acceptance.
- At least an hour of real-use native/JVM resource profiling; short lifecycle loops do not prove leak freedom.

Plaintext persistence, no expiration, no secret detection, and no secure erase are documented limitations. Pause is not encryption. Images, search, sync, launch at login, and installers remain future work.

## 7. Environment and local troubleshooting notes

Last tested environment: macOS 26.6.2 (25G83), Apple Silicon, Homebrew OpenJDK/javac 17.0.14, JavaFX 21.0.10, Swift 6.4, and macOS SDK 26.5. These are recorded versions, not recommendations of the latest patch level.

The Swift build targets macOS 13 and uses Swift language mode 5. The installed default SDK had been newer than the Swift compiler; scripts prefer an available 26.5 SDK unless `NASHCLIP_SDK` overrides it. Inspect current tools if this fails on a different machine.

Use the existing scripts rather than changing system Java/SDK defaults. JavaFX dependencies cache in `java-fx/target/m2`. `mvn package` does not invoke `scripts/test.py`; a successful Maven build is not a substitute for those tests.

The project directory was not a Git checkout at last inspection. `.github/modernize/` contains separate tooling hooks; no release CI was established by this work. Leave unrelated local tooling and `bitbucket.log` alone.

Memory maintenance: update dates and evidence after an actual change or run. Keep the pending permission state accurate. Do not copy private clipboard text, screen contents, credentials, or unrelated task details into these files.
