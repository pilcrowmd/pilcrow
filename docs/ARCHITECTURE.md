# Architecture — Pilcrow

Pilcrow is a native Android Markdown reader and editor built with Kotlin and Jetpack Compose. It
renders Markdown to native views — **no WebView anywhere in the rendering path** — and is organized
around Clean Architecture, MVVM, and unidirectional data flow (UDF) with strict layer boundaries.
The central thesis: high-fidelity, reliable, private reading of Markdown is best served by a fully
native pipeline you can measure, test, and control pixel-for-pixel — not an embedded browser.

This document is verified against the source under
[`app/src/main/java/com/pilcrowmd/`](../app/src/main/java/com/pilcrowmd/).

---

## Design principles

**1. Clean Architecture + MVVM + UDF.** Dependencies point inward only. The UI observes immutable
state and emits events; it holds no business logic. State lives in a ViewModel and is exposed as
`StateFlow`. Parsing and search live in the Domain layer. All I/O lives in the Data layer behind
interfaces.

```
┌──────────────────────────────────────────────────────────────┐
│ UI — Jetpack Compose (passive, state-driven)                  │
│ MainScreen, Preview, Editor, Toolbar, SearchBar, …            │
└───────────────────────────┬──────────────────────────────────┘
                            │ observes StateFlow / sends events
┌───────────────────────────▼──────────────────────────────────┐
│ State — MarkdownViewModel                                      │
│ Holds UI state, orchestrates use cases, scopes coroutines.    │
│ No view inflation, no direct file/disk access.                │
└───────────────────────────┬──────────────────────────────────┘
                            │ calls
┌───────────────────────────▼──────────────────────────────────┐
│ Domain — pure Kotlin, no Android, no I/O                       │
│ ParseMarkdownHeadingsUseCase, SearchMarkdownUseCase,          │
│ markdown/Frontmatter                                           │
└───────────────────────────┬──────────────────────────────────┘
                            │ uses
┌───────────────────────────▼──────────────────────────────────┐
│ Data — I/O & persistence behind interfaces                    │
│ FileRepository (SAF), StorageManager (DataStore),             │
│ MarkwonRenderer, PdfExporter                                   │
└──────────────────────────────────────────────────────────────┘
```

**2. Why native, no WebView.** Rendering Markdown to native `Spannable`s and `View`s (via Markwon)
rather than HTML in a WebView buys: predictable performance with no JS runtime, a smaller and
safer attack surface, exact design control over typography and color, fully offline rendering, and
the ability to keep rendered blocks aligned with editor character offsets for search and
navigation. Math renders as native JLatexMath bitmaps; unsupported diagram syntax degrades to a
code block by default.

**3. Simplicity within strict boundaries.** Each layer is small and cohesive. A Composable never
parses Markdown or reads a file. The ViewModel never inflates a view or blocks on disk. A Domain
use case never imports an Android type. Incidental complexity (structure, control flow) is attacked
relentlessly; the four Essential Safeguards (below) are never simplified away.

---

## Layers

### UI — Jetpack Compose (passive)

[`ui/`](../app/src/main/java/com/pilcrowmd/ui/) — rendering and interaction only; all state is
hoisted out.

- [`ui/screen/MainScreen.kt`](../app/src/main/java/com/pilcrowmd/ui/screen/MainScreen.kt) — root
  layout; provides the active color scheme to the tree via a `CompositionLocal` and hosts the
  reader/editor, toolbar, search, headings drawer, and settings.
- [`ui/components/Preview.kt`](../app/src/main/java/com/pilcrowmd/ui/components/Preview.kt) +
  [`PreviewScroll.kt`](../app/src/main/java/com/pilcrowmd/ui/components/PreviewScroll.kt) — the
  reader: a `RecyclerView` driven by a Markwon adapter of native block views, with deterministic
  scroll-position preservation.
- [`ui/components/Editor.kt`](../app/src/main/java/com/pilcrowmd/ui/components/Editor.kt) +
  [`EditorController.kt`](../app/src/main/java/com/pilcrowmd/ui/components/EditorController.kt) — the
  source editor (Sora) wrapper and its control surface (search navigation, heading jump).
- [`ui/components/`](../app/src/main/java/com/pilcrowmd/ui/components/) — `Toolbar`, `SearchBar`,
  `HeadingsDrawer` (table of contents), `SettingsScreen`, `LicensesScreen`, `WelcomeScreen`.

The UI reads colors exclusively through the token layer (Safeguard 4); it never hardcodes hex.

### State — `MarkdownViewModel`

[`viewmodel/MarkdownViewModel.kt`](../app/src/main/java/com/pilcrowmd/viewmodel/MarkdownViewModel.kt)
is the single state hub. It exposes `StateFlow`s the UI collects and runs all side effects inside
`viewModelScope`, offloading blocking I/O to `Dispatchers.IO`.

Representative state: the open document (URI, content, display name, **dirty** flag computed by
comparison against the loaded baseline, not a manual toggle), reader/editor `mode`, detected
line-ending format, per-file scroll/cursor anchors, search query/matches/index, extracted headings,
PDF export state, and persisted preferences (theme, separate preview/editor font scales, font set,
line numbers, opt-in cloud diagram rendering).

The ViewModel **never** parses Markdown itself (it calls the use cases), **never** touches the
filesystem directly (it calls `FileRepository`), and **never** inflates views.

### Domain — pure Kotlin

[`domain/`](../app/src/main/java/com/pilcrowmd/domain/) is framework-agnostic and JVM-testable with
no emulator.

- [`domain/usecase/ParseMarkdownHeadingsUseCase.kt`](../app/src/main/java/com/pilcrowmd/domain/usecase/ParseMarkdownHeadingsUseCase.kt)
  — parses the document and extracts headings with block positions, sharing the renderer's block
  model so the table of contents stays in lockstep with what is drawn.
- [`domain/usecase/SearchMarkdownUseCase.kt`](../app/src/main/java/com/pilcrowmd/domain/usecase/SearchMarkdownUseCase.kt)
  — computes ordered in-document matches addressed by block offset.
- [`domain/markdown/Frontmatter.kt`](../app/src/main/java/com/pilcrowmd/domain/markdown/Frontmatter.kt)
  — YAML frontmatter detection/parsing that **never rewrites the source** (preserving offsets).
- [`domain/model/`](../app/src/main/java/com/pilcrowmd/domain/model/) — immutable results
  (`HeadingNode`, `SearchMatch`, `ThemeMode`).

### Data — I/O & persistence behind interfaces

[`repository/`](../app/src/main/java/com/pilcrowmd/repository/),
[`storage/`](../app/src/main/java/com/pilcrowmd/storage/),
[`rendering/`](../app/src/main/java/com/pilcrowmd/rendering/),
[`export/`](../app/src/main/java/com/pilcrowmd/export/).

- **`FileRepository`** ([interface](../app/src/main/java/com/pilcrowmd/repository/FileRepository.kt),
  [`LocalFileRepository`](../app/src/main/java/com/pilcrowmd/repository/LocalFileRepository.kt)) —
  reads/writes through the Storage Access Framework (`ContentResolver`), takes persistable URI
  permissions to reopen the last file, and performs the atomic save (Safeguard 1). UTF-8 in and out.
- **`StorageManager`** ([interface](../app/src/main/java/com/pilcrowmd/storage/StorageManager.kt),
  [`LocalStorageManager`](../app/src/main/java/com/pilcrowmd/storage/LocalStorageManager.kt)) —
  Jetpack **Preferences DataStore**, held as a process-level singleton so Activity recreation never
  spawns a duplicate store. Persists theme, font scales, the line-numbers flag, the cloud-diagram
  toggle, the recent-files list, and per-file scroll anchors (block index + intra-block offset for
  font-scale-stable restore).
- **`MarkwonRenderer`** ([MarkwonRenderer.kt](../app/src/main/java/com/pilcrowmd/rendering/MarkwonRenderer.kt))
  — a single configured Markwon instance reused across the reader, search highlighting, and PDF
  export. See the rendering pipeline below.
- **`PdfExporter`** ([PdfExporter.kt](../app/src/main/java/com/pilcrowmd/export/PdfExporter.kt),
  [PdfContentLayoutBuilder.kt](../app/src/main/java/com/pilcrowmd/export/PdfContentLayoutBuilder.kt))
  — off-screen native rendering to a PDF. See the PDF pipeline below.

---

## Dependency injection — a manual composition root

Pilcrow uses a single hand-wired composition root rather than an annotation-processing framework.

- [`di/AppContainer.kt`](../app/src/main/java/com/pilcrowmd/di/AppContainer.kt) — the interface
  declaring every dependency: `fileRepository`, `storageManager`, `markwonRenderer`, `pdfExporter`,
  the two use cases, and `appInfo`.
- [`di/DefaultAppContainer.kt`](../app/src/main/java/com/pilcrowmd/di/DefaultAppContainer.kt) —
  builds them as **lazy singletons** from the application context (never an Activity), so they are
  safe to hold across configuration changes and retained ViewModels.
- [`PilcrowApplication.kt`](../app/src/main/java/com/pilcrowmd/PilcrowApplication.kt) — the
  `Application` subclass that owns the container.
- ViewModels are created through a factory rather than constructed directly:

```kotlin
val factory = MarkdownViewModel.provideFactory(container)
val viewModel: MarkdownViewModel = viewModel(factory = factory)
```

**Why manual?** One visible composition root, no scattered instantiation, and no Service Locator —
while avoiding reflection and annotation-processing overhead (and the toolchain friction it brought
with the native editor dependency). Dependencies are explicit and trivially substitutable in tests.

---

## Rendering pipeline — Markdown to native views

```
content string
   → Markwon.parse  (CommonMark AST + plugins)
   → MarkwonAdapter (maps each block type to a custom Entry)
   → native Views   (TextView / table view / scrollable code view)
   → RecyclerView   (recycles block views as the reader scrolls)
```

[`MarkwonRenderer`](../app/src/main/java/com/pilcrowmd/rendering/MarkwonRenderer.kt) configures one
Markwon instance with: the CommonMark core; GFM tables, strikethrough, and task lists; linkify; the
inline parser; **JLatexMath** for `$…$` and `$$…$$` math (single-`$` inline math is added by
[`SingleDollarMathInlineProcessor.kt`](../app/src/main/java/com/pilcrowmd/rendering/SingleDollarMathInlineProcessor.kt)
with currency-aware delimiter rules shared with search; async executor to avoid blocking the UI
thread, with an error handler that falls back to raw text); **Prism4j** syntax highlighting (grammars wired
through a generated locator, [`GrammarBundle.kt`](../app/src/main/java/com/pilcrowmd/rendering/GrammarBundle.kt));
limited safe HTML; and a custom non-mutating frontmatter plugin
([`FrontmatterPlugin.kt`](../app/src/main/java/com/pilcrowmd/rendering/FrontmatterPlugin.kt)). On
init it pre-warms the math fonts by rendering a dummy formula on a background thread so the first
real equation does not stutter.

Block rendering is handled by per-type entries registered in
[`RecyclerAdapterEntries.kt`](../app/src/main/java/com/pilcrowmd/rendering/RecyclerAdapterEntries.kt):
[`ProseBlockEntry`](../app/src/main/java/com/pilcrowmd/rendering/ProseBlockEntry.kt) (paragraphs,
lists, blockquotes, headings),
[`FencedCodeBlockEntry`](../app/src/main/java/com/pilcrowmd/rendering/FencedCodeBlockEntry.kt) (code
with syntax highlighting, a copy button, and opt-in cloud diagram rendering),
[`TableBlockEntry`](../app/src/main/java/com/pilcrowmd/rendering/TableBlockEntry.kt),
[`LatexBlockEntry`](../app/src/main/java/com/pilcrowmd/rendering/LatexBlockEntry.kt), and
[`FrontmatterBlockEntry`](../app/src/main/java/com/pilcrowmd/rendering/FrontmatterBlockEntry.kt).
Wide code and tables scroll horizontally on screen via
[`HorizontalScrollSupport.kt`](../app/src/main/java/com/pilcrowmd/rendering/HorizontalScrollSupport.kt);
search matches are styled at bind time by
[`SearchHighlighter.kt`](../app/src/main/java/com/pilcrowmd/rendering/SearchHighlighter.kt).

**The editor** uses [Sora Editor](https://github.com/Rosemoe/sora-editor) (a mature native Kotlin
code editor) with TextMate Markdown highlighting, an optional line-number gutter, soft wrap, and
native undo/redo. The reader and editor each preserve their own scroll position — and the editor
its caret offset — across mode toggles, so returning to a mode restores where you left it. Position
is not translated between modes (the reader uses a block-level anchor; the editor a pixel offset).

---

## The four Essential Safeguards

These are guarantees, enforced in code and verified by tests.

### 1. No data loss — crash-safe saves

A save either fully succeeds or fails cleanly; the file is never left truncated or half-written —
even if the process is killed mid-write. A SAF `"wt"` open truncates the target *before* the new
bytes land, so an in-memory backup is not enough (it evaporates on process death). Instead
[`LocalFileRepository.saveFile()`](../app/src/main/java/com/pilcrowmd/repository/LocalFileRepository.kt)
uses a durable **write-ahead log**: it stages the full new content to
[`PendingSaveJournal`](../app/src/main/java/com/pilcrowmd/repository/PendingSaveJournal.kt) (in
`noBackupFilesDir`, never an evictable cache dir) and `fsync`s it *before* the target is touched.
Only then does it open the target `"wt"`, write, and `fsync`; on durable success it discards the
staged slot inside a `NonCancellable` block. If the process dies in the truncation window, the
staged content survives and `recoverPendingSaves()` replays it on the next launch. A failed save
returns a clear error and is surfaced to the user (Safeguard 1 is never swallowed silently).

`recoverPendingSaves()` runs once per process on a background scope at launch and shares
`journalMutex` with `saveFile`. Crucially, `readFile()` takes that **same lock**, so a file-load on
launch can never observe a target while recovery is streaming into it (the recovery↔read startup
race). Under the lock, if a recoverable slot still exists for the file, `readFile` serves the durable
WAL content directly rather than the possibly-truncated target — so even an immediate edit-and-save
on launch keeps the full content, and recovery converges to the identical bytes. The save write path
is untouched by this coordination.

**Verified by**
[`LocalFileRepositoryAtomicSaveTest`](../app/src/test/java/com/pilcrowmd/repository/LocalFileRepositoryAtomicSaveTest.kt):
a process-death mid-write is recovered from the durable WAL on the next launch, a clean open-failure
leaves the target intact with no stale slot, an interrupted recovery retries successfully, and a read
on launch serves the durable WAL content (never the truncated target) so an immediate edit-and-save
never loses data — modelling true interruption, not merely an in-process exception.

### 2. Round-trip fidelity — content preservation

Saving writes back exactly what the user wrote; Markdown is not normalized, reflowed, or
reformatted. The one documented bound: a file with **mixed** line endings is written back uniformly
in its dominant style (see below).

- **Line endings** — `MarkdownViewModel` detects the dominant line ending (LF or CRLF) on load and
  re-applies that format on save (`detectLineEnding` / `applyLineEnding`), so a CRLF file stays
  CRLF and an LF file stays LF. A file that mixes both is normalized to the dominant style on save;
  per-line ending preservation is out of scope for v1.
- **Frontmatter** —
  [`FrontmatterPlugin.processMarkdown()`](../app/src/main/java/com/pilcrowmd/rendering/FrontmatterPlugin.kt)
  returns the input unchanged; detection sets a flag but never edits the text, keeping editor,
  search, and TOC offsets aligned.
- **Encoding** — reads and writes are always UTF-8, with no BOM rewriting or lossy re-encoding.

**Verified by**
[`MarkdownViewModelLineEndingTest`](../app/src/test/java/com/pilcrowmd/viewmodel/MarkdownViewModelLineEndingTest.kt)
(LF, CRLF, mixed-dominant, empty, no-trailing-newline, and blank-line-only round-trips).

### 3. Render never crashes — graceful degradation

Unsupported or malformed syntax degrades; it never throws or mangles surrounding content.

- The JLatexMath plugin's error handler returns `null` on a parse failure, falling back to the raw
  source text instead of crashing.
- Block entries wrap binding in `try/catch` and fall back to a plain representation on error.
- Diagrams (Mermaid) render as a code block unless the user opts into cloud rendering; a network
  failure there falls back to the code block too.
- Unknown node types fall through to the default prose entry.

**Verified by**
[`MarkwonRendererLargeFileTest`](../app/src/test/java/com/pilcrowmd/rendering/MarkwonRendererLargeFileTest.kt)
(renders a ~5,000-line mixed-block document without throwing) and
[`FrontmatterParserTest`](../app/src/test/java/com/pilcrowmd/rendering/FrontmatterParserTest.kt)
(malformed frontmatter falls back instead of breaking).

### 4. Design-token fidelity — color only from the token layer

Color comes exclusively from the token system; no hardcoded hex appears in composables or block
entries. [`ui/theme/Color.kt`](../app/src/main/java/com/pilcrowmd/ui/theme/Color.kt) defines a
`PilcrowColorScheme` (~21 tokens) with three instances — `DarkColorScheme` (the v1 default),
`LightColorScheme` (a warm-cream palette), and `PrintColorScheme` (white page, dark text, for PDF).
The active scheme is published through a `CompositionLocal` (`LocalMDColors`) and read with
`mdColors()` in Compose; block entries receive the scheme as a parameter. Switching theme is a
token swap with no layout change.

**Verified by** the Roborazzi visual-regression suite below, which captures every sample in both
Dark and Light.

---

## PDF export pipeline

[`PdfExporter`](../app/src/main/java/com/pilcrowmd/export/PdfExporter.kt) +
[`PdfContentLayoutBuilder`](../app/src/main/java/com/pilcrowmd/export/PdfContentLayoutBuilder.kt)
render to a native `PdfDocument`, reusing the same Markwon instance and block entries as the reader
(so the PDF matches the screen) but with `PrintColorScheme`:

1. Stream the document off-screen in two passes that hold only **O(1) block views at a time** (a
   type-pooled holder is rebound per block), so peak memory stays bounded regardless of document
   size — a ~5,000-line document is never inflated all at once. The document is parsed once; Pass 1
   measures each block at A4 content width to compute its bounds.
2. Compute page breaks at block boundaries so blocks are not split across pages where avoidable
   (a single over-tall block is sliced as a last resort).
3. Draw each A4 page (595×842 pt) — Pass 2 — inflating only the blocks that fall on that page, at a
   print scale that lands body text near 11 pt, with a page-number footer.
4. Resolve LaTeX synchronously during the measure pass so detached views show formulas, not raw
   source; malformed formulas degrade exactly as on screen.
5. Clean up on error: on any write/flush/close failure, the partial file is deleted
   (`DocumentsContract.deleteDocument`, best-effort) before the error is rethrown — so an I/O error
   leaves no half-written export. Unlike a Markdown save, the export has no write-ahead log, so a hard
   process-kill mid-write could still leave a partial PDF; that is acceptable because a PDF is a
   regenerable export, not the user's source (Safeguard 1's no-loss guarantee covers the `.md`, which
   is untouched by export).

---

## Testing & quality strategy

**Unit tests** — beyond the safeguard tests above, the suite also covers:
[`ParseMarkdownHeadingsUseCaseTest`](../app/src/test/java/com/pilcrowmd/domain/usecase/ParseMarkdownHeadingsUseCaseTest.kt),
[`SearchMarkdownUseCaseTest`](../app/src/test/java/com/pilcrowmd/domain/usecase/SearchMarkdownUseCaseTest.kt),
[`PdfExporterTest`](../app/src/test/java/com/pilcrowmd/export/PdfExporterTest.kt), and storage/UI
utility tests (font scale, scroll-anchor persistence, intra-block offsets).

**Visual regression (Roborazzi)** — the core suite renders the Markdown samples
(headings, emphasis, lists, task lists, blockquotes, fenced code, tables, rules, links/images,
inline and block LaTeX, degraded diagram, frontmatter, kitchen-sink) across 3 font scales
(0.85×, 1.0×, 1.6×) and 2 themes (Dark, Light) under Robolectric native graphics, pinned to a fixed
device configuration for determinism. Layout or typography regressions fail the build:

```bash
./gradlew verifyRoborazziDebug   # gate
./gradlew recordRoborazziDebug   # re-record after an intentional visual change
```

**Style & static analysis** — ktlint (Kotlin conventions) and detekt (complexity, naming,
structure), both baselined and run on every build.

**CI** — [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on every push and PR to
`main`:

```bash
./gradlew clean
./gradlew assembleDebug ktlintCheck detekt testDebugUnitTest verifyRoborazziDebug bundleRelease lintRelease --rerun-tasks
```

> **Why the mandatory `clean` step.** Kotlin is pinned to 2.3.10 to match the metadata version of
> the Sora editor dependency (`editor-bom:0.24.5`). That toolchain carries a known
> incremental-compiler quirk that can cache a stale state and emit phantom `Unresolved reference`
> errors on valid code — a false *negative* (green turned red), never a false pass. So `clean` runs
> first and `--rerun-tasks` defeats Gradle task-output caching; a from-scratch compile is the only
> result we trust. Treat `clean` as a gate, not an optimization to skip.

---

## Source map

```
com.pilcrowmd/
├── PilcrowApplication.kt          app subclass; owns the AppContainer
├── MainActivity.kt                single activity; intent routing
├── di/                            composition root
│   ├── AppContainer.kt            interface (declares all dependencies)
│   ├── DefaultAppContainer.kt     lazy-singleton implementation
│   └── AppInfo.kt                 version metadata
├── viewmodel/
│   └── MarkdownViewModel.kt       state hub (StateFlow, UDF)
├── domain/                        pure Kotlin, no Android/I/O
│   ├── usecase/                   ParseMarkdownHeadings, SearchMarkdown
│   ├── markdown/Frontmatter.kt    non-mutating YAML frontmatter
│   └── model/                     HeadingNode, SearchMatch, ThemeMode
├── repository/                    file I/O behind an interface
│   ├── FileRepository.kt
│   └── LocalFileRepository.kt     SAF + atomic save (Safeguard 1)
├── storage/                       persistence behind an interface
│   ├── StorageManager.kt
│   └── LocalStorageManager.kt     Preferences DataStore
├── rendering/                     native Markdown rendering (no WebView)
│   ├── MarkwonRenderer.kt         plugin config; shared instance
│   ├── RecyclerAdapterEntries.kt  block-type → entry registry
│   ├── ProseBlockEntry.kt         FencedCodeBlockEntry.kt  TableBlockEntry.kt
│   ├── LatexBlockEntry.kt         FrontmatterBlockEntry.kt FrontmatterPlugin.kt
│   ├── HorizontalScrollSupport.kt SearchHighlighter.kt
│   └── GrammarBundle.kt           Prism4jTheme.kt
├── export/                        PDF rendering
│   ├── PdfExporter.kt             pagination + atomic write
│   └── PdfContentLayoutBuilder.kt off-screen block layout
└── ui/                            Jetpack Compose (passive)
    ├── screen/MainScreen.kt       root layout + theme provider
    ├── components/                Preview, PreviewScroll, Editor, EditorController,
    │                              Toolbar, SearchBar, HeadingsDrawer,
    │                              SettingsScreen, LicensesScreen, WelcomeScreen
    └── theme/                     Color.kt (tokens), Typography.kt, Spacing.kt, FontSet.kt
```

## Navigating the code

1. Start at [`MainActivity.kt`](../app/src/main/java/com/pilcrowmd/MainActivity.kt) →
   [`MainScreen`](../app/src/main/java/com/pilcrowmd/ui/screen/MainScreen.kt).
2. Read the `StateFlow` declarations in
   [`MarkdownViewModel`](../app/src/main/java/com/pilcrowmd/viewmodel/MarkdownViewModel.kt) to see
   the full app state at a glance.
3. For rendering, follow
   [`MarkwonRenderer`](../app/src/main/java/com/pilcrowmd/rendering/MarkwonRenderer.kt) →
   [`RecyclerAdapterEntries`](../app/src/main/java/com/pilcrowmd/rendering/RecyclerAdapterEntries.kt)
   → the individual block entries.
4. Each safeguard maps to a named test — start there to understand the guarantee, then read the
   implementation it protects.
