# Contributing to Pilcrow

Thanks for your interest in contributing to Pilcrow! Bug reports, feature ideas, documentation,
and design feedback are all very welcome — please open an issue. We're finalizing a Contributor
License Agreement (CLA), so we're **not yet accepting external code contributions (pull
requests)** — see [Code contributions (CLA pending)](#code-contributions-cla-pending) below.

Please read our **[Code of Conduct](CODE_OF_CONDUCT.md)** before contributing. For **security
issues, do not open a public issue** — follow **[SECURITY.md](SECURITY.md)** instead.

---

## Ways to contribute

- **Report a bug** — open a [bug report](https://github.com/pilcrowmd/pilcrow/issues/new/choose)
  with steps to reproduce, the Markdown that triggers it if relevant, and your Pilcrow/Android
  versions.
- **Request a feature** — open a [feature request](https://github.com/pilcrowmd/pilcrow/issues/new/choose)
  with the problem you're trying to solve. Note that v1 is intentionally focused on reading and
  editing Markdown beautifully and safely, offline (see the [roadmap](README.md#roadmap)); requests
  that respect that scope are easiest to land.
- **Ask a question** — use [Discussions](https://github.com/pilcrowmd/pilcrow/discussions).
- **Contribute code** — we'd love your help here too, but code pull requests are on hold until our
  CLA is in place. See [Code contributions (CLA pending)](#code-contributions-cla-pending) just
  below.

---

## Code contributions (CLA pending)

We are finalizing a Contributor License Agreement (CLA). **Until it's in place, we are not yet
accepting external code contributions (pull requests).**

If you'd like to contribute code, please open an issue to discuss it first — that way the work is
already aligned on scope and approach when the door opens. We'll announce when the CLA process is
live and code PRs can be accepted. Once it is, all code contributions will require a one-time CLA
sign-off.

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

Pilcrow is a single-activity Jetpack Compose app built with **Clean Architecture + MVVM +
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

> **Note:** until the CLA is in place (see
> [Code contributions (CLA pending)](#code-contributions-cla-pending)), external code PRs are not
> yet being accepted — this process currently applies to maintainers.

1. Branch from `main` (e.g. `feat/toc-jump` or `fix/save-crlf`).
2. Write or update tests, then implement.
3. Format (`./gradlew ktlintFormat`) and run the **full gate** locally — it must pass.
4. Use [Conventional Commits](https://www.conventionalcommits.org/) (e.g.
   `feat(reader): show search match count`, `fix(editor): keep scroll after undo`, `docs: …`).
   Reference issues with `Closes #123`.
5. Open a PR and fill in the [template](.github/PULL_REQUEST_TEMPLATE.md): what changed and why, how
   you tested, and confirmation that the gate passed and the safeguards/layer boundaries hold.

**Reviewers look for:** layer boundaries respected; the four safeguards intact; tests added and the
gate green; surgical diffs; conventional commits; and re-recorded goldens for intentional visual
changes.

---

## License

By contributing, you agree your contributions are licensed under the **GNU General Public License
v3.0 or later (GPL-3.0-or-later)** — see [LICENSE](LICENSE). Third-party dependencies are documented
in [LICENSES.md](LICENSES.md).
