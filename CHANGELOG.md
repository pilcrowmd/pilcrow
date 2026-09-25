# Changelog

All notable changes to Pilcrow are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.10] - 2026-09-25

### Added

- **A setting to wrap long lines in code blocks.** *Settings → Wrap long lines in code blocks.*
  With it on, a long line in a code block wraps inside the block in the reader, instead of
  scrolling sideways. **Off by default** — side-scrolling stays exactly as it was unless you turn
  this on. The editor already wraps, and exported PDFs already wrap code, so neither changes.
  Requested by **[HuevosKicker](https://github.com/HuevosKicker)**. It is off by default because
  **[unoukujou](https://github.com/unoukujou)** asked for side-scrolling to stay.

### Changed

- **Headings are sized as a clear, even scale.** The top headings are a little smaller and the lower
  ones larger, so no heading is ever smaller than the body text: H1 to H6 now run 29, 23, 21, 19, 18
  and 17 sp, where H5 and H6 used to be smaller than a paragraph. Headings also get more room above
  them and a little below. Pinch-to-zoom scales headings and text together, as before. Exported PDFs
  use the same sizes.
- **The light theme is light all the way through.** In the light theme, code blocks, inline code and
  a diagram shown as source used to keep the dark theme's dark background inside the cream page.
  They now use light backgrounds with readable colours. The headings drawer and the shade behind it
  are light too, where before they stayed dark, and footnote markers have their own colour. The dark
  theme and exported PDFs are unchanged.

### Fixed

- **Markdown code blocks are highlighted.** A ```` ```markdown ```` block used to render in one flat
  colour. Headings, emphasis, links, list markers and inline code are now coloured. Other languages
  gain colour for tokens that were left plain, such as CSS selectors, regular expressions and
  built-ins. Reported by **[HuevosKicker](https://github.com/HuevosKicker)**.
- **Diff blocks are highlighted.** ```` ```diff ```` and ```` ```patch ```` blocks now show added
  lines in green, removed lines in red, and `@@` hunk headers in their own colour. Exported PDFs are
  unchanged by this and the entry above. Reported by **[HuevosKicker](https://github.com/HuevosKicker)**.
- **Turning the phone no longer re-opens the file you launched the app with.** If Pilcrow was
  opened from another app with *Open with*, that file was opened again every time the screen
  rotated or the system switched between light and dark mode. On a document you had not edited,
  it replaced whatever you had opened since, with no warning. On an edited document, you were
  asked whether to discard your changes for a file you had not asked to open. The launch file is
  now opened once.
- **Opening a file from another app now opens that file, not the last one you had open.** If
  Pilcrow was not already running, *Open with* on a file often showed the file you had open last
  time instead, because the app's reopening of that last file finished after the new one and
  replaced it. The file you open now takes priority over reopening the last one.
- **With *Open in edit mode* on, opening a document no longer shows the reader first.** It used to
  appear in the reader for a moment before switching to the editor, and on a large file the app could
  pause briefly while it prepared a view it was about to throw away. Right after the app starts, the
  reader can still show for a moment before the editor.
- **While Pilcrow reopens your last document, the buttons you cannot use yet now look disabled.**
  *Open MD File*, *Create MD File*, *Browse all files* and your recent files are dimmed until it has
  finished. Before, they looked normal but did nothing when tapped.
- **A metadata card that falls back to plain text keeps its own look.** If the reading font fails to
  load, the card at the top of a document with front matter shows its text plainly, as designed. It
  could also pick up a code block's monospace font and background and lose its line spacing. It now
  keeps the card's own background and spacing and uses the standard font.

## [1.0.9] - 2026-09-21

### Fixed

- **Saving a copy can no longer attach the new file to the wrong document.** If another document
  opened while *Save a copy* was still writing — a file arriving from another app, or discarding
  changes to open a pending one — the new file's name and location were attached to whichever
  document had just appeared, rather than the one whose text had been written. The next save then
  wrote that document over the copy you had just made, destroying it. The copy itself was always
  written correctly; it was the *next* save that did the damage. Saving a copy now attaches the new
  location only to the document whose text actually went to disk.
- **Starting to open a file no longer unlocks the controls during a save.** The check that stops a
  second save, a close or an export from starting mid-write shared its state with file loading, so
  beginning to open a document switched every one of those guards back on while the write was still
  running. Each guard now reads a dedicated flag that only a write can hold.
- **The system Back button no longer closes a file while it is being saved.** The toolbar's close
  button was already disabled during a save; Back was not, so it could close the document mid-write
  — after which the file you had just closed was added back to your recent files and reopened on the
  next launch. Back now does nothing during a save, exactly as the close button does. Dismissing
  search or the drawer with Back is unaffected.

## [1.0.8] - 2026-09-18

### Added

- **You can start a new document without finding a file first.** The welcome screen has a second
  button, **Create MD File**, which opens a blank document straight in the editor. Write first,
  then save it wherever you like — the file is created when you save, not before.
- **A setting to open documents ready to write.** *Settings → Open in edit mode.* With it on, a
  document opens in the editor instead of the reader, so writing no longer costs a tap on every
  file. Off by default, and the reader is still one tap away.

### Fixed

- **Pinch-to-zoom in the reader no longer leaves blocks at different sizes.** While your fingers
  were still on the screen, headings and paragraphs could drift apart — a title could even end up
  smaller than before you started — and everything then snapped to one size the moment you let go.
  Text now grows and shrinks together throughout the gesture, so what you see while pinching is
  what you get when you release. Reading size only; your file is never touched.
- **Right-to-left text in the editor now starts at the right edge.** Arabic and other right-to-left
  lines were laid out from the left, which is the wrong side of the screen to begin reading on. The
  characters themselves were always in the correct order — only the alignment of the row was wrong.
  Left-to-right lines are unchanged, and so are your file's contents. Reported and fixed by
  **[yshalsager](https://github.com/yshalsager)** (Youssif Shaaban Alsager).
- **A document you start while your last file is still opening is no longer overwritten.** When
  Pilcrow reopened your most recent file at startup, the welcome screen stayed fully usable while
  that file was still being read. You could tap **Create MD File** and begin writing — and the
  moment the old file finished loading it took the screen, silently discarding what you had just
  typed. The screen now tells you a document is opening and waits: **Open**, **Create MD File**,
  **Browse all files** and the recent files become available again as soon as it has finished. Only
  those few seconds are affected, and nothing already saved to disk is touched.

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
