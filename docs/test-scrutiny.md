# Test scrutiny and coverage

## Scope

This review examined all three suites for weak assertions, failure paths, isolation, lifecycle checks, and measurable coverage. The acceptance target is 90% of the explicitly defined core production lines, with separate reports and regression floors for all Java/Swift production source. Whole-application 85–90% coverage has **not** been achieved.

The core includes clipboard capture, retention and size limits, pins, settings, storage, migration, locking, and saved geometry where implemented. App startup, UI wiring, paste adapters, and native helpers need separate attention. See [the testing guide](testing.md) for exact scopes, commands, and remaining manual checks.

## Measured result — 2026-10-02

Command: `python3 scripts/test.py --check-coverage --report-dir test-results/coverage-audit`.

| Version | Core line coverage | Core decision coverage | All Java/Swift production lines |
| --- | --- | --- | --- |
| Swing | 349/367 — **95.10%** | 202/214 branches — **94.39%** | 1,163/1,824 — **63.76%** |
| JavaFX | 391/424 — **92.22%** | 225/252 branches — **89.29%** | 1,223/1,591 — **76.87%** |
| Swift | 637/667 — **95.50%** | 384/415 regions — **92.53%** | 1,501/2,405 — **62.41%** |

Swift regions and Java branches are different counters and should not be compared as equivalent metrics. Overall source coverage includes unexecuted entry points and app controllers. It excludes native Objective-C helper implementations.

Results: **146 automated checks/groups passed, 9 coverage gates passed, 0 failures**. Twelve build checks and six fixture-generation steps also passed; these are separate from test totals. The runner self-test group contains 15 checks. **27 manual checks remain skipped**. Grouped programs contain multiple assertions, so these numbers are not individual assertion counts or a percentage of features verified.

Mutation verification: **16/16 seeded regressions detected**, with **8/8 unchanged control runs passing**. The first attempt exposed a surviving Swift history-limit mutation; the added in-memory assertion detected it on the final run.

Environment: macOS 26.6.2 (25G83), Apple Silicon, OpenJDK 17.0.14, JavaFX 21.0.10, Swift 6.4 with the macOS 26.5 SDK, and JaCoCo 0.8.15. This is evidence for this host and source snapshot, not a supported-platform matrix.

Local run artifacts (ignored by version control):

- [Test and gate results](../test-results/coverage-audit/summary.json)
- [Coverage counts and per-file results](../test-results/coverage-audit/coverage/summary.json)
- [Mutation results](../test-results/mutations-final/summary.json)
- [Source checksums](../test-results/coverage-audit/source-checksums.json)

Regenerate these artifacts with the documented commands; they are not expected to exist in a fresh checkout.

## Findings and changes

| Finding | Change | Evidence |
| --- | --- | --- |
| Passing build/fixture steps inflated the headline test count. | Separate `test`, `build`, `fixture`, and `manual` result categories. | Runner self-tests verify report counts. Grouped programs still contain multiple assertions; totals are not assertion counts. |
| A successful exit without test output could pass. A named failure with exit 0 could return success to callers. | Reject silent/malformed test output and propagate named failures regardless of exit code. | Self-tests cover silence, malformed/negative/nonfinite durations, empty names, failure with exit 0, crash after pass, timeout, missing executable, JSON and XML. |
| The Use test began with the drawer hidden. | Open the drawer and assert that precondition before clicking Use, then verify full copied text, one delivery, and hiding. | Swing and JavaFX feature suites. |
| A click-inside latch test checked visibility before an incorrect close animation could finish. | Let the event loop advance beyond the close duration before asserting visibility. | All three drawer regression programs. |
| Paste tests did not verify enough intermediate behavior. | Assert destination identity, verification order, no activation on early clipboard mismatch, interrupt preservation, release on all outcomes, and monitor shutdown. | Shared `PasteServiceTests`, run against both Java implementations. |
| Corrupt-file and boundary coverage was incomplete. | Add malformed history/pin counts, missing values, duplicated settings fields, invalid types, symlink read/write rejection, lock overlap/reacquisition, Unicode escapes, and original-file preservation. | `DataValidationTests` and `StorageValidationTests`. |
| Retention testing relied heavily on examples. | Add 80 reproducible generated Java cases with independent invariants for limits, pin priority, stable order, valid text, idempotence, and UTF-16 persistence. Include a nonempty expected-output check so dropping everything cannot satisfy the test. | `history.seeded-retention-and-roundtrip-invariants`, both Java variants. |
| Swift could overwrite a valid saved position with invalid dimensions or a fraction outside 0–1. | Share position validation between load and save; reject invalid saves before writing. | `swift.position.invalid-save-preserves-valid-position` failed before the fix and passes afterward. |
| Swift test failures could lose buffered named output. | Flush each named result; throw from `finish()` to unwind fixture cleanup before process failure. | Failed validation and mutation runs retain the failing case name. |
| A Swift round-trip test masked a broken in-memory limit because loading trimmed entries again. | Assert the in-memory policy result directly before persistence. | An ordinary-limit mutation survived before this assertion. |
| There was no measured coverage or enforceable threshold. | Add JaCoCo/LLVM instrumentation, all-source inclusion checks, fresh output per run, machine-readable/HTML reports, and coverage gates. | `scripts/coverage_tools.py`, `tests/coverage-policy.json`, and runner coverage self-tests. |

The paste failure message also incorrectly said the window did not regain “platform”; it now says “focus,” with a regression assertion.

## How assertion strength was checked

`scripts/mutation-checks.py` seeds selected regressions in temporary source copies. It first runs unchanged controls, then requires a named assertion failure for each mutation. It never edits the working production sources. The mutations exercise the clip boundary, pin cap, ordinary limit, exact-window verification, clipboard-race checks, target release, and Swift Unicode identity.

This set is deliberately small and risk-focused. A successful result means these particular faults are detected; it does not establish a general mutation score or prove other faults would be detected.

## Remaining coverage gaps

1. **Startup and real app wiring:** controller fixtures assemble selected components without starting the full tray/hotkey/clipboard stack. This misses launch failures, callback wiring, actual settings dialogs, and some quit paths. Swift `AppController` and the application entry points remain unexecuted by these tests.
2. **Native paste and focus:** the user confirmed working window targeting and paste on 2026-10-02. Java's automated decision tests use fake OS effects, and Swift's successful native paste path remains largely uncovered by automation. Broader Accessibility, multiwindow/Spaces, and permission-change scenarios still need recorded acceptance results. This is a coverage gap, not a confirmed current wrong-window defect.
3. **Native registration and adapters:** Swift Carbon hotkeys and Java Objective-C helper implementation coverage are not supplied by the reports. Java helper-protocol and JNI empty-context checks do not substitute for successful OS integration.
4. **Memory and resource growth:** repeated timer, window, process, and context cycles catch some lifecycle regressions. They do not prove leak freedom. Long-running native/JVM profiling and peak-memory measurement remain release gates.
5. **Visual and accessibility behavior:** executing a view/layout function does not validate every rendered pixel, theme contrast, keyboard action, VoiceOver label, screen configuration, or display removal.
6. **Filesystem fault injection:** malformed files, symlinks, bounded reads, and blocked destinations are tested. Full-disk errors, forced partial writes, atomic-move fallback, power loss, and concurrent external modification are not exhaustively injected.

The next improvements should automate startup/shutdown with injected service boundaries and add a Swift paste-platform seam, followed by controlled macOS end-to-end tests. Keep these gaps visible instead of excluding their source from coverage.
