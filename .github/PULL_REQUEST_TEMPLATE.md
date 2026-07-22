<!--
Thanks for contributing to Pilcrow! Please read CONTRIBUTING.md first.
Keep diffs surgical: touch only what the change requires.
-->

## What & why

<!-- What does this PR change, and what problem does it solve? Link related issues. -->

Closes #

## How I tested

<!-- Commands run, devices/emulators used, and what you observed. -->

- [ ] Ran the full gate locally: `./gradlew clean && ./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest verifyRoborazziDebug --rerun-tasks`

## Checklist

- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
- [ ] Diff is surgical and matches the surrounding style.
- [ ] **Layer boundaries respected** — no business logic or file I/O in composables/ViewModels; no hardcoded color hex.
- [ ] **The four Essential Safeguards hold** (no data loss, round-trip fidelity, render never crashes, design-token fidelity).
- [ ] Tests added/updated for new logic; safeguard tests still green.
- [ ] Visual changes: Roborazzi goldens re-recorded **only** for intentional changes, and committed so reviewers see the diff.
- [ ] Docs updated if behavior or architecture changed.
