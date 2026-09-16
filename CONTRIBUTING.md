# Contributing to PilcrowMD

**Ideas are the contribution this project runs on.** Bug reports, feature requests, reproductions,
design feedback, a note that something reads wrong on your device — these are genuinely wanted, and
they are what moves the app forward. Several of the changes queued for the next release came from
people outside the project raising them on the public tracker — including the right-to-left editor
fix described below. Please
[open an issue](https://github.com/pilcrowmd/pilcrow/issues/new/choose).

**Outside code is not accepted, at least for now.** This is a decision, not a queue and not a
process you are waiting on: the codebase has a single maintainer, who holds sole copyright over it.
There is no Contributor License Agreement and none is coming, so there is nothing to sign and
nothing to wait for. If that changes, this file changes with it.

Please read our **[Code of Conduct](CODE_OF_CONDUCT.md)** before taking part. For **security
issues, do not open a public issue** — follow **[SECURITY.md](SECURITY.md)** instead.

---

## Ways to contribute

- **Report a bug** — open a [bug report](https://github.com/pilcrowmd/pilcrow/issues/new/choose)
  with steps to reproduce, the Markdown that triggers it if relevant, and your PilcrowMD/Android
  versions.
- **Request a feature** — open a [feature request](https://github.com/pilcrowmd/pilcrow/issues/new/choose)
  with the problem you're trying to solve. Note that v1 is intentionally focused on reading and
  editing Markdown beautifully and safely, offline (see the [roadmap](README.md#roadmap)); requests
  that respect that scope are easiest to land.
- **Send design feedback** — screenshots, typography and spacing notes, anything that looks wrong on
  your screen. Open an issue; they are read.
- **Ask a question** — open an issue. There is no discussion forum; issues are the one place.

---

## Code contributions

**Pull requests that add code are declined.** The codebase has a single author and a single
copyright holder, and it stays that way at least for now. Please do not spend your time on a patch
expecting it to be merged — an issue describing the same change is worth far more here, and is
acted on.

**There is a route for a change you want badly enough to have written.** Describe it in an issue, or
point at your own branch or patch so the behaviour is unambiguous. You keep the copyright in
whatever you wrote; the change is then implemented here independently; and **you are credited by
name in the changelog entry that ships it.** The credit is the point, and it is not optional.

That route is in use right now: an outside contributor opened a pull request for right-to-left row
alignment in the editor, gave written permission on the thread for the fix to be applied without
merging the pull request, and the changelog entry that ships it will name them.

**Translations are the one thing that will open, and they are not open yet.** The app's text is
still written into the code rather than collected into a strings file, so there is nothing to hand a
translator. That work is planned. When there is a file to translate, it will be said here and on
the tracker — please don't start one before then, because there is nothing yet for it to attach to.

---

## Development setup

**Prerequisites:** JDK 21, the Android SDK with API 36, and a device or emulator running Android 8.0
(API 26) or newer. The Gradle wrapper pins the build tooling — no global Gradle install is needed.

```bash
git clone https://github.com/pilcrowmd/pilcrow.git
cd pilcrow

./gradlew clean assembleDebug      # build the debug APK
./gradlew installDebug             # install to a connected device/emulator
```

> **Always build with `clean` for any result you intend to trust.** Kotlin 2.3.10's incremental
> compiler can poison its cache and emit phantom `Unresolved reference` errors on code that compiles
> cleanly from scratch. A from-scratch compile is the only reliable signal; CI runs `clean` first
> and so should you.

### Run the full quality gate locally

Run the same gate CI runs before you push:

```bash
./gradlew clean
./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest verifyRoborazziDebug bundleRelease lintRelease --rerun-tasks
```

This builds cleanly, checks style (ktlint) and static analysis (detekt), runs the unit tests,
verifies the visual-regression screenshots, and builds + lints the release variant
(`bundleRelease`, `lintRelease`). Auto-fix style issues with:

```bash
./gradlew ktlintFormat
```

---

## Project architecture

PilcrowMD is a single-activity Jetpack Compose app built with **Clean Architecture + MVVM +
unidirectional data flow** and strict layer boundaries:

- **UI (Compose)** is passive and state-driven — **no business logic**, and **colors come only from
  the design-token layer (no hardcoded hex)**.
- **State (`MarkdownViewModel`)** exposes UI state as `StateFlow` and orchestrates everything — it
  does **no direct file I/O and no Markdown parsing**.
- **Domain** holds pure-Kotlin parsing/search use cases — framework-free and JVM-testable.
- **Data** hides file I/O and persistence behind interfaces (`FileRepository` over the Storage
  Access Framework; `StorageManager` over Jetpack DataStore).
- Dependencies are wired through a single hand-written composition root (a manual `AppContainer`),
  and Markdown is rendered to native views — **never a WebView**.

**Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making structural changes.**

---

## Coding standards

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html);
  ktlint enforces them.
- Static analysis is via detekt; rules live in `config/detekt/detekt.yml` with a baseline in
  `config/detekt/baseline.xml`.
- Keep functions focused (roughly ≤50 lines), files cohesive (roughly ≤800 lines), and nesting
  shallow (≤4 levels — prefer early returns).
- Name things for intent. Composables are PascalCase with no `Composable` suffix.
- **Never hardcode a color hex** in a composable or block entry — read from `mdColors()` (Compose)
  or take the scheme as a parameter (block entries).
- Keep diffs **surgical**: touch only what the task requires, and match the surrounding style.

---

## The four Essential Safeguards (never weaken these)

These are correctness requirements, not nice-to-haves. Simplify the structure *around* them, never
the safeguard itself.

1. **No data loss** — saves are atomic; a failed save aborts cleanly and never leaves a truncated or
   corrupted file.
2. **Round-trip fidelity** — saving writes back exactly what the user wrote; no silent
   normalization, reflow, or line-ending changes.
3. **Render never crashes** — unsupported or malformed syntax degrades gracefully; the renderer
   never throws or mangles surrounding content.
4. **Design-token fidelity** — all color comes from the token layer; no hardcoded hex.

If you touch save logic, file I/O, or rendering, your change must keep the corresponding safeguard
test green (see below) — and add to it.

---

## Tests

- **Add tests for new logic.** Pure domain logic is JVM-testable without an emulator. Aim for solid
  coverage of new code, especially error paths and edge cases (target ≥80% on new logic).
- **The safeguards have dedicated tests** — keep them passing and extend them when you touch the
  area:
  - atomic save with injected write failure —
    [`LocalFileRepositoryAtomicSaveTest`](app/src/test/java/com/pilcrowmd/repository/LocalFileRepositoryAtomicSaveTest.kt)
  - LF/CRLF round-trip —
    [`MarkdownViewModelLineEndingTest`](app/src/test/java/com/pilcrowmd/viewmodel/MarkdownViewModelLineEndingTest.kt)
  - large-file crash resistance —
    [`MarkwonRendererLargeFileTest`](app/src/test/java/com/pilcrowmd/rendering/MarkwonRendererLargeFileTest.kt)
- Run the unit tests with `./gradlew testDebugUnitTest`.
- **Visual changes** must keep the Roborazzi suite green. If a change is an *intentional* visual
  update, re-record the goldens and commit them so reviewers see the diff:
  ```bash
  ./gradlew recordRoborazziDebug
  ./gradlew verifyRoborazziDebug
  ```

---

## Pull request process

> **This is the maintainer workflow**, kept here in the open because the build it describes is
> the one that produces the released app — the reproducible-build story F-Droid verifies depends on
> every change going through exactly these steps. Outside pull requests are not accepted (see
> [Code contributions](#code-contributions)); read this as a description of how the app is built,
> not as an invitation to open one.

1. Branch from `main` (e.g. `feat/toc-jump` or `fix/save-crlf`).
2. Write or update tests, then implement.
3. Format (`./gradlew ktlintFormat`) and run the **full gate** locally — it must pass.
4. Use [Conventional Commits](https://www.conventionalcommits.org/) (e.g.
   `feat(reader): show search match count`, `fix(editor): keep scroll after undo`, `docs: …`).
   Reference issues with `Closes #123`.
5. Keep your local tooling out of the diff. Editor, IDE and local agent configuration directories
   are yours, not the project's — this repository does not ignore them on your behalf. Put them in
   your own global excludes (`git config --global core.excludesFile`) rather than in `.gitignore`,
   and check `git status` before you stage instead of relying on `git add -A`.
6. Open a PR and fill in the [template](.github/PULL_REQUEST_TEMPLATE.md): what changed and why, how
   you tested, and confirmation that the gate passed and the safeguards/layer boundaries hold.

**Reviewers look for:** layer boundaries respected; the four safeguards intact; tests added and the
gate green; surgical diffs; conventional commits; and re-recorded goldens for intentional visual
changes.

---

## License

PilcrowMD is licensed under the **GNU General Public License v3.0 or later
(GPL-3.0-or-later)** — see [LICENSE](LICENSE). You are free to use, study, modify and redistribute
it on those terms, forks included. Third-party dependencies are documented in
[LICENSES.md](LICENSES.md).

Because outside code is not accepted, there is nothing for you to license to this project and no
agreement to sign. If you describe a change and it is implemented here, the copyright in whatever
you wrote stays yours.
