<div align="center">

# PilcrowMD

**A beautiful, reliable, private Markdown reader for Android.**

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.pilcrowmd&referrer=utm_source%3Dgithub%26utm_campaign%3Dreadme_badge)
[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.pilcrowmd/)

**Install:** [Google Play](https://play.google.com/store/apps/details?id=com.pilcrowmd&referrer=utm_source%3Dgithub%26utm_campaign%3Dreadme_text) · [F-Droid](https://f-droid.org/packages/com.pilcrowmd/) · the APK is also attached to every [GitHub Release](https://github.com/pilcrowmd/pilcrow/releases).

[![CI](https://github.com/pilcrowmd/pilcrow/actions/workflows/ci.yml/badge.svg)](https://github.com/pilcrowmd/pilcrow/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white)](#build--run)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?logo=kotlin&logoColor=white)](#tech-stack)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4.svg?logo=jetpackcompose&logoColor=white)](#tech-stack)

**[pilcrowmd.com](https://pilcrowmd.com)** – screenshots, changelog, privacy policy

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-reader-dark.png" alt="PilcrowMD rendering a Markdown document – headings, prose, lists, and a blockquote, all rendered natively" width="320">

</div>

---

## What & why

The web is full of Markdown – LLM answers, READMEs, notes, technical docs – and most Android apps
render it in a WebView wrapped in ads and trackers. PilcrowMD takes the opposite approach.

PilcrowMD renders Markdown **natively** – no WebView, no JavaScript runtime, no network calls to read
your files. The result is fast, typographically careful rendering of full GitHub-Flavored Markdown
(tables, code, math, task lists, frontmatter) with a reading experience that looks designed rather
than dumped.

- **Beautiful.** Serif body type, real syntax highlighting, rendered LaTeX, and a calm dark theme
  (with a warm-cream light theme) built from an exact design-token system.
- **Reliable.** Atomic saves that never truncate your file, round-trip fidelity that preserves
  your line endings and frontmatter (a file with mixed line endings is written back uniformly in
  its dominant style), and a renderer that degrades gracefully instead of crashing on syntax it
  doesn't support.
- **Private.** Files are read through Android's Storage Access Framework and stay on your device.
  No accounts, no ads, no analytics, no telemetry, no crash reporting, no network in the reading path. Free software under the GPL.

## Features

- **Full GitHub-Flavored Markdown** – headings, emphasis, lists, task lists, blockquotes, tables,
  fenced code, thematic breaks, links, and pictures stored with the note (web pictures are not downloaded).
- **Native syntax highlighting** for fenced code blocks, with a one-tap copy button.
- **LaTeX math** – inline (`$…$` or `$$…$$`) and block `$$…$$`, rendered as crisp native bitmaps
  (no WebView). Single-`$` parsing is currency-aware, so `$5 and $10` stays plain text.
- **Reader + source editor** – toggle between a polished reader and a real code editor with
  Markdown highlighting, a line-number gutter, and soft wrap.
- **In-document search** with match highlighting and next/previous navigation.
- **Table of contents** drawer generated from your headings, with tap-to-jump.
- **YAML frontmatter** rendered as a tidy metadata card.
- **Export to PDF** – paginated and print-styled, on white pages.
- **Two themes** – a default dark theme and a warm-cream light theme.
- **Per-file memory** – reopens your last file at the position you left it; keeps a recents list.
- **Adjustable type** – independent font scaling for reader and editor.
- **Graceful by design** – diagrams and unsupported syntax degrade to readable code blocks rather
  than breaking the page (Mermaid can optionally render via the cloud, off by default).
- **Offline & ad-free** – minimum Android 8.0, no required network permission for reading.

## Screenshots

<div align="center">

<table>
  <tr>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-reader-dark.png" width="190" alt="Reader: headings, lists and a blockquote"><br><sub>Reader: headings, lists and a blockquote</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-code-blocks-dark.png" width="190" alt="Syntax-highlighted code blocks"><br><sub>Syntax-highlighted code blocks</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-math-table-chemistry-dark.png" width="190" alt="LaTeX math, chemical equations and a table"><br><sub>LaTeX math, chemical equations and a table</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04a-same-doc-light.png" width="190" alt="The same document in the warm-cream light theme"><br><sub>The same document in the warm-cream light theme</sub></td>
  </tr>
  <tr>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-editor-dark.png" width="190" alt="Source editor with line numbers"><br><sub>Source editor with line numbers</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06-pdf-export.png" width="190" alt="Exporting the document to PDF"><br><sub>Exporting the document to PDF</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/07-mermaid-dark.png" width="190" alt="Mermaid diagrams (optional cloud rendering)"><br><sub>Mermaid diagrams (optional cloud rendering)</sub></td>
    <td align="center" width="25%"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/08-welcome.png" width="190" alt="Home screen with recent files"><br><sub>Home screen with recent files</sub></td>
  </tr>
</table>

</div>

## Architecture overview

PilcrowMD is a single-activity Jetpack Compose app following **Clean Architecture + MVVM** with
unidirectional data flow and strict layer boundaries:

- **UI (Compose)** is passive and state-driven – no business logic, colors only from design tokens.
- **State (`MarkdownViewModel`)** exposes UI state as `StateFlow` and orchestrates everything; it
  never does I/O or parsing directly.
- **Domain** holds pure-Kotlin parsing and search use cases – testable with no emulator.
- **Data** hides file I/O and persistence behind interfaces (`FileRepository` over the Storage
  Access Framework; `StorageManager` over Jetpack DataStore).

Dependencies are wired through a single hand-written composition root (a manual `AppContainer`), and
Markdown is rendered to native views with [Markwon](https://github.com/noties/Markwon) – **never a
WebView**. The four Essential Safeguards (no data loss, round-trip fidelity, crash-free rendering,
design-token fidelity) are enforced in code and protected by tests.

**→ Full details in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).**

## Build & run

**Requirements:** JDK 21, Android SDK with API 36, and `minSdk` 26 (Android 8.0) for the target
device. The Gradle wrapper pins the build tooling – no global Gradle install needed.

```bash
git clone https://github.com/pilcrowmd/pilcrow.git
cd pilcrow

# Build the debug APK
./gradlew clean assembleDebug

# Install to a connected device / emulator
./gradlew installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

> **Build from `clean` for any result you intend to trust.** PilcrowMD pins Kotlin to 2.3.10 to match
> the metadata version of its editor dependency (Sora `editor-bom:0.24.5`). That toolchain carries a
> known incremental-compiler quirk: it can cache a stale state and report phantom `Unresolved
> reference` errors on code that compiles cleanly from scratch – a false *negative* (green turned
> red), never a false pass. A from-scratch build is therefore the source of truth, and CI runs
> `clean` first so it never trusts a poisoned cache.

## Testing & quality

Quality is enforced on every push and pull request by [CI](.github/workflows/ci.yml), which runs the
full gate from a clean build:

```bash
./gradlew clean
./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest verifyRoborazziDebug bundleRelease lintRelease --rerun-tasks
```

- **Unit tests** cover the critical paths, including the
  Essential Safeguards: crash-safe save with process-death recovery from a durable write-ahead log
  (`LocalFileRepositoryAtomicSaveTest`), LF/CRLF round-trip fidelity
  (`MarkdownViewModelLineEndingTest`), and a ~5,000-line document renders without crashing
  (`MarkwonRendererLargeFileTest`).
- **Visual-regression goldens** via [Roborazzi](https://github.com/takahirom/roborazzi) – every
  Markdown sample × 3 font scales × 2 themes, plus the Welcome, search, and GitHub-teaser screens,
  rendered under Robolectric and pinned to a fixed device config. Any layout or typography drift
  fails the build.
- **Static analysis & style** – [ktlint](https://github.com/JLLeitschuh/ktlint-gradle) and
  [detekt](https://github.com/detekt/detekt), both baselined and run in the gate.
- **License diligence** – all third-party dependencies and bundled fonts are tracked in
  [LICENSES.md](LICENSES.md) and surfaced in-app, and verified compatible with GPL-3.0-or-later as a
  combined work.

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.3 |
| UI | Jetpack Compose (Material 3) |
| Architecture | Clean Architecture + MVVM + UDF, manual DI |
| Async | Coroutines & Flow |
| Markdown rendering | [Markwon](https://github.com/noties/Markwon) (native, no WebView) |
| Syntax highlighting | [Prism4j](https://github.com/noties/Prism4j) |
| Math | JLatexMath (via Markwon) |
| Code editor | [Sora Editor](https://github.com/Rosemoe/sora-editor) |
| Persistence | Jetpack DataStore (Preferences) |
| File access | Storage Access Framework |
| Testing | JUnit, Robolectric, [Roborazzi](https://github.com/takahirom/roborazzi), MockK |
| Fonts | [Source Serif 4](https://github.com/adobe-fonts/source-serif) & [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) (OFL) |

## Roadmap

PilcrowMD v1 is intentionally focused – read and edit Markdown beautifully and safely, offline.
Directions under consideration for future releases:

- Document collections / multi-file libraries.
- Additional export targets and richer print options.
- Optional offline diagram rendering.
- Wider syntax coverage (footnotes, definition lists).

Nothing here is a commitment; the v1 safeguards and native-only constraint always come first.

## Contributing

Bug reports, feature ideas, reproductions and design feedback are very welcome – please open an
issue. Outside code contributions are not accepted, at least for now;
**[CONTRIBUTING.md](CONTRIBUTING.md)** is the canonical statement of what is wanted and how a change
you care about can still reach the app. Please also read our
**[Code of Conduct](CODE_OF_CONDUCT.md)**. Security issues should follow
**[SECURITY.md](SECURITY.md)** rather than a public issue.

## License

PilcrowMD is **free software**, licensed under the **GNU General Public License v3.0 or later
(GPL-3.0-or-later)**. Copyright © 2026 pleree.

It comes with **no warranty**. See [LICENSE](LICENSE) for the full text or
<https://www.gnu.org/licenses/>. The copyleft license is deliberate: it keeps PilcrowMD and its
derivatives free and open, and prevents closed-source or ad-laden clones.

The app's own license is **distinct from** the licenses of its third-party dependencies and bundled
assets, which are documented in [LICENSES.md](LICENSES.md) and surfaced in-app under
**Settings → About → Open source licenses**.

## Acknowledgements

PilcrowMD stands on excellent open-source work:

- [Markwon](https://github.com/noties/Markwon) and [Prism4j](https://github.com/noties/Prism4j) –
  native Markdown rendering and syntax highlighting.
- [Sora Editor](https://github.com/Rosemoe/sora-editor) – the native code editor.
- [JLatexMath](https://github.com/opencollab/jlatexmath) – math typesetting.
- [Roborazzi](https://github.com/takahirom/roborazzi) – screenshot testing.
- [Source Serif 4](https://github.com/adobe-fonts/source-serif) and
  [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) – the typefaces, under the SIL Open
  Font License.
