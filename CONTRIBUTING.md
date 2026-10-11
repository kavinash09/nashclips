# Contributing to nashclip

nashclip is maintained by **Avinash Kumar** and explores the same clipboard workflow in Swing, JavaFX, and native Swift. Contributions are welcome under the project's [Apache 2.0 license](LICENSE). Preserve the license and attribution in [NOTICE](NOTICE).

## Before you start

- Read the [installation guide](INSTALL.md) and [architecture walkthrough](docs/architecture-walkthrough.md).
- Use an issue to describe the problem and intended behavior before a large feature or redesign.
- For small fixes, open a focused pull request with reproduction steps and verification results.
- Report vulnerabilities using [SECURITY.md](SECURITY.md). Never attach personal clipboard history, credentials, or unredacted screenshots.

Keep discussions respectful. Explain technical disagreements with examples and evidence; avoid personal remarks.

## Development

Run commands from the repository root unless a guide says otherwise. Each variant has its own build/run scripts. Python 3.10+ is required for test and packaging tooling.

```sh
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 scripts/test.py --list
```

Before the combined suite, prepare JavaFX's local dependency cache:

```sh
cd java-fx
mvn -q -s maven-settings.xml -Dmaven.repo.local=target/m2 compile
cd ..
python3 scripts/test.py --headless
```

Run the relevant desktop suite for UI changes on a logged-in Mac. For shared storage or policy changes, run all three variants and the cross-version checks:

```sh
python3 scripts/test.py
```

See [testing](docs/testing.md) for coverage gates, mutation checks, isolation, and manual acceptance. CI runs core checks and packaging previews; it does not establish real paste behavior or release readiness.

## Implementation conventions

- Keep UI work on the toolkit's UI thread and avoid introducing blocking disk or process work there.
- Keep clipboard monitoring, retention policy, persistence, drawer behavior, and native integration separate.
- Preserve exact-window verification and clipboard-change guards during paste.
- Keep history formats, UTF-16 limits, pin limits, and locking compatible across all three implementations.
- Use temporary storage and fake/private clipboards in tests. Test native paste only with a disposable destination and synthetic content.
- Do not add copied text to logs. Retain pause controls and size limits.
- Explain variant differences rather than claiming feature parity that has not been implemented.

## Pull request checklist

- Describe the user-visible change and why it is needed.
- Include the variant, environment, exact test commands, and results; identify untested behavior.
- Add a regression test when fixing consequential behavior. Keep documentation-only checks proportionate.
- Update user docs and the Unreleased section of [CHANGELOG.md](CHANGELOG.md).
- Include migration/backward-compatibility details if persistence changes.
- Preserve `LICENSE` and `NOTICE`; disclose any new third-party dependencies and their licenses.
- Exclude build outputs, logs, clipboard data, credentials, and machine-specific IDE settings.

Publishing and signing are separate maintainer tasks described in [RELEASING.md](RELEASING.md). A green CI run does not authorize or publish a release.
