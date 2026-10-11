# Release verdict — 2026-10-02

## Decision

**NO-GO for a public production release of any of the three versions.**

The project is ready for further controlled development testing with disposable text. Passing automated checks supports the tested behavior; it does not clear the remaining distribution, native integration, responsiveness, or sustained-resource gates.

This was a fresh rerun of the complete available suites, coverage gates, selected mutation checks, and build commands, plus a review of the remaining high-risk implementation paths. It was not an exhaustive test of every OS interaction. Production and test source checksums matched the previous audited snapshot before this run. No application behavior was changed in this recheck.

## Fresh automated results

- **146 automated checks/groups passed; 0 failed.**
- **All 9 coverage gates passed.** The runner's total of 155 includes these gates.
- **16/16 seeded mutations detected; 8/8 unchanged controls passed.**
- Twelve suite build checks and six cross-version fixture steps passed separately.
- **27 manual acceptance entries remain skipped in the automated report.** The user subsequently confirmed working window targeting and paste; the full scenario matrix has not been recorded.

| Version | Core line coverage | All Java/Swift production line coverage |
| --- | --- | --- |
| Swing | 95.10% | 63.76% |
| JavaFX | 92.22% | 76.87% |
| Swift | 95.50% | 62.41% |

The core subset passes its 90% gate. These results do not mean 90% of the complete app is tested; the overall column includes unexecuted startup and UI code. Native Objective-C helpers are outside the instrumentation denominator.

Evidence: [suite results](../test-results/release-recheck/summary.json), [coverage counts](../test-results/release-recheck/coverage/summary.json), [mutation results](../test-results/release-mutations/summary.json), and [source checksums](../test-results/release-recheck/source-checksums.json).

Commands:

```sh
python3 scripts/test.py --check-coverage --report-dir test-results/release-recheck
python3 scripts/mutation-checks.py --report-dir test-results/release-mutations
sh java-swing/build.sh
sh nashclip-swift/build.sh
# From java-fx:
mvn -q -s maven-settings.xml -Dmaven.repo.local=target/m2 package
```

## Confirmed build and distribution findings

| Version | Finding | Release consequence |
| --- | --- | --- |
| Swing | `sh java-swing/build.sh` compiles source and native helpers into development output. The launcher expects Java and the project directory. | No standalone, distribution-signed macOS package or clean-machine installation evidence. |
| JavaFX | `mvn package` succeeds after fetching missing Maven plugins. The resulting JAR has no `Main-Class` manifest entry and contains no native helpers. JavaFX, the runtime, and helpers remain external requirements. | The JAR is not a standalone desktop application. `mvn package` also does not execute the project's separate Python-driven suites. |
| Swift | `sh nashclip-swift/build.sh` builds an arm64 `.app`. Strict signature verification passes. Signature inspection reports `Signature=adhoc`, no TeamIdentifier, and no hardened-runtime flag. The unsandboxed `spctl --assess --type execute --verbose=4` returns **rejected**, exit 3. | The current artifact fails the distribution acceptance check on this machine. Developer ID signing/notarization and clean-machine installation need to be completed and verified. |

The first JavaFX packaging attempt failed because offline Maven lacked a build plugin. An online retry succeeded; this was a cache prerequisite, not an application compilation defect. The first sandboxed Gatekeeper attempt returned an internal code-signing error; the explicit unsandboxed retry produced the rejection reported above. The rejection is a distribution-policy result, not evidence that the app is malware.

Apple documents the Developer ID and notarization workflow for direct distribution in [Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).

Build and signature logs are in [the recheck output directory](../test-results/release-recheck/). [JavaFX artifact metadata](../test-results/release-recheck/fx-artifacts.json) records the inspected manifest and helper list. Generated artifacts are local and ignored by version control.

## Remaining release blockers

### 1. Working paste needs broader recorded acceptance coverage

**User confirmation, 2026-10-02:** window targeting and automatic paste are working in current use. The former Java wrong-window defect is fixed and should not be presented as an open observed failure. The remaining item is validation across the release scenario matrix.

The Java suites exercise paste decisions through fake OS effects; JNI checks use empty targets. Swift tests cover missing-target fallbacks but do not verify successful external-app paste. This run did not send real paste keystrokes.

Before shipping, record successful and safely refused operations with multiple windows of the same app, Spaces/fullscreen, app termination, permission denial/revocation, and focus/clipboard changes during restoration. Verify tray and global-shortcut entry paths too. The automated runner records 27 manual entries as skipped; the user's confirmation adds evidence for working paste without establishing results for every listed scenario.

### 2. UI responsiveness remains a confirmed architectural risk

Swing's `ClipboardWatcher` uses a Swing timer and JavaFX's watcher uses a `Timeline`; both poll and call their handlers on the UI thread. `NashClip.persistHistory` and `NashClipApp.persistHistory` serialize and write history synchronously from those callbacks. Swift's `ClipboardModel` is `@MainActor` and its capture path calls `storage.saveHistory` synchronously. Swift accessibility calls also execute on the main actor.

The retained-text limits help bound stored data, but clipboard APIs may materialize large text before the rejection check. Slow clipboard producers, disk writes, or AX calls can stall interaction. No latency or transient-memory acceptance measurements clear this risk.

Required work: use bounded, ordered persistence off the UI thread with coalescing and reliable shutdown/error behavior; respect each clipboard toolkit's thread requirements. Measure capture, resizing, scrolling, and animation under large input and slow/failing storage.

### 3. Sustained GUI/native resource behavior is not certified

Short timer, process, window, lock, and JNI lifecycle regressions pass through the automated suite. They are useful checks, but no hour-long Instruments/JVM soak with live-object, native-allocation, thread, descriptor, and window baselines was performed in this recheck. **No leak-free claim is justified.**

Required evidence: resources plateau after warmup during repeated real use, and Quit removes windows, tray items, helpers, callbacks, and pending paste work. Profile real AX targets and graphics resources, not only empty native contexts.

### 4. Runtime and release-environment validation is incomplete

The tested Java runtime is OpenJDK 17.0.14 and the POM selects JavaFX 21.0.10. Later maintenance releases are documented by [Oracle](https://www.oracle.com/java/technologies/javase/17-0-19-relnotes.html) and [Gluon](https://gluonhq.com/products/javafx/openjfx-21-release-notes/). This confirms maintenance updates exist; it is not a finding of a particular exploitable vulnerability in nashclip.

Select the runtimes to ship, review their dependencies, then run the suites and clean-install acceptance on the actual packaged artifacts. The Swift build targets macOS 13 but this run tests only macOS 26.6.2 on Apple Silicon; compatibility with the declared minimum and Intel has not been established.

## Privacy limitation to retain in release documentation

Clipboard history is plaintext, including persisted pins. Pause and limits reduce capture but do not provide secret detection, encryption, or expiration. Deleting a clip does not erase the system clipboard, legacy data, or backups. These are documented product limitations, not claims of secure erasure or automatic protection for passwords. A general clipboard tool needs a clear user-facing explanation before sensitive data is retained.

## Conditions for changing the verdict

1. Produce a standalone package for each version intended for release, with the required runtime/helpers and validated signing/notarization and installation.
2. Complete the real-app paste, permissions, shortcut, tray, dialogs, accessibility, and display acceptance matrix using disposable text.
3. Address UI-thread blocking and record responsiveness and large-input results.
4. Complete sustained native/JVM profiling and investigate retained-resource growth.
5. Run the automated suites against the final source/runtime combination and test the final packaged artifacts on the supported platform matrix.

The detailed procedures are in [the testing guide](testing.md). The earlier implementation audit and scope limitations remain available in [the production audit](production-audit.md) and [test scrutiny report](test-scrutiny.md).
