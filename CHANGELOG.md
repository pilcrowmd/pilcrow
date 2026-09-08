# Changelog

All notable changes to Pilcrow are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.7] - 2026-09-08

### Fixed

- **Tapping a footnote now lands on the footnote.** It used to stop about a screen short of the
  definition, which looked like the jump half-working — and because a footnote definition is always
  the last thing in a document, this happened to every footnote in every document. The definition
  you asked for is now at the top of the screen, and is briefly highlighted so your eye finds it.
- **Footnote markers are easier to tap.** The raised number is small, and a tap that just missed it
  did nothing. A near miss now counts. **The number itself is unchanged in size** — only the area
  that responds to your thumb grew.
- **Your recent files are visible when you open the app.** With a few files in the list, the RECENT
  heading and the list itself sat below the bottom of the screen in portrait, so every launch began
  with a scroll. They are now in view, and the "Open MD File" button stays fully on screen.

## [1.0.6] - 2026-09-02

### Changed

- **The app is now called PilcrowMD.** The name under the icon and the wordmark on the welcome
  screen both change. The icon itself, the app's identity on your device and your files are
  unaffected — this is a name change and nothing else.
- **The paragraph mark behind the welcome wordmark sits higher, and is back to its original
  size.** It had been enlarged and moved down, which left it crossing the wordmark in portrait
  and running below the bottom of the screen in landscape.

### Fixed

- **The "Open MD File" button could sit completely off screen with the phone held sideways**,
  with nothing to indicate it was there. The space above the button now gives way when the
  screen is short, so the button stays reachable in landscape.

## [1.0.5] - 2026-08-25

No user-facing changes — the app behaves exactly as 1.0.4.

### Changed

- The complete corresponding source for this release is published.
- The Gradle distribution is pinned by SHA-256 checksum, so the build verifies the contents of
  the toolchain it downloads and not only its URL — a prerequisite for reproducible builds.
- Store listing metadata is now tracked in the repository alongside the source.

## [1.0.4] - 2026-08-24

### Added

- **Footnotes** — `[^1]` references and `[^1]: …` definitions now render, with tap-to-jump
  and a link back to the reference. Definitions render in place; references renumber 1..n by
  first use.
- **Plain text files** — `.txt` files open in the reader, with a per-file long-line overflow
  toggle.
- **Chemistry formulas** — `\ce{…}` (mhchem) notation renders inside math.

### Fixed

- **A long-standing freeze when opening large files.** Line-ending detection scanned the
  document twice with a regex on the main thread, which degraded quadratically — a 1.3 MB file
  could block the app for tens of seconds and a 3 MB file for minutes. Detection is now a single
  linear pass off the main thread. Present since 1.0.0.
- **A crash when entering the editor** on some devices, caused by the shared editor view being
  re-attached while still parented.
- **Inline code and raw HTML were silently dropped inside table cells** in every previous
  release. Table cell rendering now recurses into unrecognised nodes by default rather than
  discarding them.
- **Search match counts** in documents containing math no longer over-count.

## [1.0.3] - 2026-08-19

Build metadata removed from the APK so F-Droid can reproduce the build byte-for-byte. No functional
changes.

## [1.0.2] - 2026-08-19

### Fixed

- **An unrenderable paragraph no longer takes the whole document down.** One rendering path was
  missing the guard the others had, so a single paragraph that failed to render could bring down
  the reader instead of just itself. It now shows a short placeholder in place of that paragraph
  and the rest of the document renders normally.

### Changed

- Crash reports from the store now arrive with readable stack traces. Build-only change: the app's
  behaviour, size and performance are unchanged.

## [1.0.1] - 2026-07-24

### Added

- **Inline math with single dollars** — `$…$` now renders inline LaTeX alongside the existing
  `$$…$$` form (currency-aware, so `$5 and $10` stays plain text).
- **Save As** — save the open document to a new location, including brand-new documents that don't
  have a file on disk yet.
- **Branded launch splash** — a brand-dark splash screen in both light and dark appearances.
- **Pinch-to-zoom in the reader** — live text reflow anchored at the gesture focal point, kept in
  sync with the Settings text size.
- **Clear-Recents confirmation** — clearing the Recents list now asks first.
- **GitHub-integration teaser** in Settings — a roadmap note with an email interest link. It only
  opens your email app; nothing is sent unless you tap send.
- **Crash-recovery escape hatch** — if recovered unsaved changes can never be committed back to the
  original file, the app now offers a way out instead of blocking the document forever.

### Fixed

- In-document search counts matches in the rendered text, and the reader makes room for the
  keyboard while searching.
- The close-without-saving confirmation dialog now survives device rotation.
- Back navigates within the app instead of exiting it.
- The file picker soft-filters to Markdown files, and its Browse-all fallback makes every file
  selectable.
- Status and navigation bars follow the app theme, so their icons stay legible in the light theme.
- The active Reader/Editor mode is unmistakable in the toolbar (accent chip on the active side).

## [1.0.0] - 2026-06-16

The first release: the initial v1 feature set.

### Added

- **Native Markdown reader** — GitHub-Flavored Markdown rendered to native Android views (no
  WebView): headings, lists, task lists, tables, blockquotes, code blocks with syntax highlighting,
  links, and images-as-alt-text on the offline path.
- **LaTeX math** rendering (inline and block) and **opt-in cloud Mermaid diagrams** (off by default;
  degrades to a code block when disabled or unavailable).
- **YAML frontmatter** shown as a metadata card rather than a raw code block.
- **Editor** built on the Sora editor with Markdown syntax awareness.
- **PDF export** of the rendered document, including long-line wrapping for code.
- **Table of contents** drawer that jumps to headings.
- **Settings**: font sets (Source Serif 4 / JetBrains Mono), separate reader/editor zoom, theme, and
  the optional Mermaid toggle.
- **Storage Access Framework** integration for scoped, user-granted file access.
- **Dark and light themes** driven entirely by a design-token layer.

### Safeguards

- **Atomic saves** — a failed save aborts cleanly and never leaves a truncated or corrupted file.
- **Round-trip fidelity** — saving writes back exactly what you wrote; line endings (LF/CRLF) and
  frontmatter are preserved, with one documented bound: a file with mixed line endings is written
  back uniformly in its dominant style.
- **Crash-resistant rendering** — unsupported or malformed syntax degrades gracefully instead of
  throwing or mangling surrounding content.

### Privacy

- No accounts, analytics, ads, or telemetry. The reading path makes no network requests by default;
  cloud Mermaid rendering is the only optional networked feature and is off by default.

[Unreleased]: https://github.com/pilcrowmd/pilcrow/commits/main
