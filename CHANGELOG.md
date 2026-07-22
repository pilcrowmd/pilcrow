# Changelog

All notable changes to Pilcrow are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-06-28

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
