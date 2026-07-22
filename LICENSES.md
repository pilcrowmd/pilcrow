# LICENSES.md — Third-Party License Inventory

**App:** Pilcrow · **Module:** `:app` · **Variant analyzed:** `release` (the shipped APK)  
**Generated:** 2026-06-12 · **Tooling:** `com.jaredsburrows.license` v0.9.8 (`./gradlew :app:licenseReleaseReport`) + manual inventory of fonts and vendored source.  
**Machine report source of truth:** `app/build/reports/licenses/licenseReleaseReport.{json,csv}`

> **Scope & disclaimer.** This is a *factual inventory classified by declared license*, not a legal opinion. Pilcrow is distributed under the **GNU General Public License v3.0 or later (GPL-3.0-or-later)**. This document records each bundled dependency's license and confirms it is compatible with distributing the app as a **combined work under GPL-3.0-or-later**. Tiers indicate that compatibility.

**Tier legend (compatibility with a GPL-3.0-or-later combined work):**
- **PERMISSIVE** (MIT/Apache-2.0/BSD/ISC/OFL/zlib/CC0) → GPL-3.0-compatible; combines freely.
- **WEAK COPYLEFT** (LGPL/MPL-2.0/EPL) → GPL-compatible with per-component conditions (attribution, unmodified use, corresponding source available).
- **STRONG COPYLEFT** (GPL/AGPL) → same copyleft family as the project license; aligned by construction, since the whole app is GPL.
- **UNKNOWN** → investigate.

---

## 1. Summary

**Shipped runtime dependencies (release variant, from plugin): 121**

| Tier | Count |
|------|-------|
| PERMISSIVE | 117 |
| WEAK COPYLEFT | 3 |
| STRONG COPYLEFT | 1 |
| UNKNOWN / UNDECLARED | 0 |

Plus, from manual inventory (not visible to the Gradle plugin): **5 bundled font families (10 files), all OFL-1.1 → PERMISSIVE**, **1 vendored source file (TextMate grammar, MIT) → PERMISSIVE**, and a **native in-app Open-source Licenses screen** (displays all deps + license texts). See §3–§4a.

### Compatibility notes

Because Pilcrow itself is GPL-3.0-or-later, copyleft dependencies are *aligned with* — not in tension with — the project license. The non-permissive licenses below are each confirmed compatible with the combined work:

**GPLv2 with Classpath Exception — 1:**
- ✅ **`ru.noties:jlatexmath-android:0.2.0`** — Declared **GPL-2.0** in its POM, but the upstream source ships under **GPLv2 *with* Classpath Exception**. The Classpath Exception removes the linking restriction, so the library combines freely into Pilcrow's GPL-3.0-or-later work. The exception text is bundled in the app at `licenses/JLaTeXMath-GPLv2-Classpath-Exception.txt` and shown in the in-app Open-source Licenses screen. See §2 and §4a.

**WEAK COPYLEFT (compatible with conditions) — 3:**
- 🟡 **`io.github.rosemoe:editor:0.24.5`** — **LGPL-2.1** (Sora Editor core). LGPL-2.1 is GPL-compatible and explicitly permits combination into a GPL-licensed work. Because Pilcrow ships as open source under GPL-3.0-or-later with its corresponding source available, LGPL §6's relink provision is satisfied by construction (users have the source and the published dependency coordinate/version, and can rebuild with a substituted editor). Used as an unmodified binary artifact, with LGPL-2.1 attribution and license text retained.
- 🟡 **`io.github.rosemoe:language-textmate:0.24.5`** — **LGPL-2.1** (same project, same handling as above).
- 🟡 **`org.eclipse.jdt:org.eclipse.jdt.annotation:2.4.100`** — **EPL-2.0**, transitive compile-time nullness annotations (via Sora). EPL is *file-level* copyleft: obligations attach only to modified EPL files. The artifact is used unmodified with its notice retained — listed for completeness.

**UNKNOWN / UNDECLARED:** none found — the plugin resolved a declared license for all 121 dependencies.

---

## 2. Flagged items — detail

### ✅ `ru.noties:jlatexmath-android:0.2.0` — GPL-2.0 vs GPLv2-with-Classpath-Exception

- **How it enters the build:** transitive dependency of `io.noties.markwon:ext-latex:4.6.2` (LaTeX math rendering — native, no WebView).
- **POM metadata (the shipped 0.2.0 artifact, from the Gradle cache):** `<name>GNU General Public License, version 2</name>` — no exception recorded.
- **Upstream reality:** the `noties/jlatexmath-android` `pom.xml` (android branch) and the underlying `opencollab/jlatexmath` declare **"GPLv2 with Classpath Exception"**. The Classpath Exception explicitly allows linking the library into an independent work.
- **Handling:** The Classpath Exception is documented verbatim in `licenses/JLaTeXMath-GPLv2-Classpath-Exception.txt`, bundled in the APK, and surfaced in the in-app Open-source Licenses screen when the user views the jlatexmath license text. The exception permits linking into Pilcrow's GPL-3.0-or-later combined work; modifications to JLaTeXMath itself remain under its own terms.
- **Bundled fonts:** the AAR ships math fonts under `assets/org/scilab/forge/jlatexmath/fonts/licences/`: `Knuth_License.txt`, `License_for_dsrom.txt`, `OFL.txt` (and, per upstream docs, some Greek glyphs are GPLv2). These ship inside the APK and carry their own (mostly permissive) terms.

### 🟡 Sora Editor — `io.github.rosemoe:editor` + `:language-textmate` (LGPL-2.1)

- **How it enters the build:** declared directly as the chosen native code-editor engine. Consumed as **unmodified binary Maven artifacts** via `editor-bom:0.24.5`.
- **GPL compatibility:** LGPL-2.1 is compatible with the GPL and may be combined into a GPL-licensed work. Since Pilcrow is distributed as open source under GPL-3.0-or-later with corresponding source available, the LGPL §6 relink obligation is inherently met — users have the full source plus the exact dependency coordinate/version and can rebuild the app with their own editor build.
- **Compliance:** used unmodified, kept as a separate `.aar` dependency at a published coordinate/version, with LGPL-2.1 license text + attribution included (and shown in-app).

### 🟡 `org.eclipse.jdt:org.eclipse.jdt.annotation:2.4.100` (EPL-2.0)

- **How it enters the build:** transitive, compile-time nullness annotations pulled via Sora `language-textmate`.
- **EPL-2.0** is per-file weak copyleft; obligations attach only to *modified* EPL files. The artifact is unmodified and its notice is retained — listed for completeness.

---

## 3. Bundled fonts (manual — not seen by the Gradle plugin)

Font binaries live in `app/src/main/res/font/`. The project ships **only OFL-licensed fonts**; the inventory confirms all bundled families are **SIL OFL 1.1 → PERMISSIVE**. `FontSet.kt` groups them into three selectable sets (Classic / Book / Modern).

| Font family | Files | SPDX | Tier | License file in repo | Modified? |
|-------------|-------|------|------|----------------------|-----------|
| Source Serif 4 | `source_serif_4_{regular,bold}.ttf` | OFL-1.1 | PERMISSIVE | ✅ `licenses/fonts/OFL-SourceSerif4.txt` | No evidence (file renamed lowercase for Android res naming only) |
| JetBrains Mono | `jetbrains_mono_{regular,bold}.ttf` | OFL-1.1 | PERMISSIVE | ✅ `licenses/fonts/OFL-JetBrainsMono.txt` | No evidence |
| IBM Plex Mono | `ibm_plex_mono_{regular,bold}.ttf` | OFL-1.1 | PERMISSIVE | ✅ `licenses/fonts/OFL-IBMPlexMono.txt` | No evidence |
| Merriweather | `merriweather_{regular,bold}.ttf` | OFL-1.1 | PERMISSIVE | ✅ `licenses/fonts/OFL-Merriweather.txt` | No evidence |
| Atkinson Hyperlegible | `atkinson_hyperlegible_{regular,bold}.ttf` | OFL-1.1 | PERMISSIVE | ✅ `licenses/fonts/OFL-AtkinsonHyperlegible.txt` | No evidence |

**OFL-1.1 conditions (all satisfied):** ⚠️ the OFL license text must accompany the distribution — ✅ included in `licenses/fonts/` and surfaced in-app; ⚠️ the fonts' **Reserved Font Names** must not be used for modified glyphs — the fonts are unmodified, and lowercasing a *filename* for Android resource rules is not a font modification and does not trigger the Reserved Font Name clause. The OFL permits bundling fonts inside distributed software.

**Status:** ✅ All five fonts have OFL-1.1 license texts in `licenses/fonts/` (Source Serif 4, JetBrains Mono, IBM Plex Mono, Merriweather, Atkinson Hyperlegible) and are surfaced in the in-app Open-source Licenses screen (native Compose modal). If any TTF was actually re-hinted/subset, confirm the internal font name was changed to avoid the Reserved Font Name.

---

## 4. Vendored source (manual — copied into the repo, not via Gradle)

| Path | Origin | SPDX | Tier | Notes |
|------|--------|------|------|-------|
| `app/src/main/assets/textmate/markdown/markdown.tmLanguage.json` | `microsoft/vscode-markdown-tm-grammar` (commit `0812fc4`, recorded in the file's `version` field) | MIT | PERMISSIVE | TextMate grammar used by the Sora editor for Markdown syntax highlighting. License text: ✅ `licenses/MIT-VSCode-Markdown-Grammar.txt`. |
| `app/src/main/assets/textmate/md-dark.json` | Own work (`"name": "Pilcrow Dark"`) | n/a (first-party) | — | Editor color theme authored for this project; no third-party obligation. |

No vendored Kotlin/Java source was found — `app/src/main/java/**` is first-party (no copied-in third-party files, no foreign license headers). The Prism syntax grammars are generated at build time by `io.noties:prism4j-bundler` (kapt) and derive from PrismJS (MIT); the shipped runtime artifact is `io.noties:prism4j` (Apache-2.0, in §5).

**Status:** ✅ MIT license text + copyright for the VSCode Markdown grammar is documented in `licenses/MIT-VSCode-Markdown-Grammar.txt` and included in the in-app Open-source Licenses screen.

---

## 4a. In-app Open-source Licenses screen

**Purpose:** Native Compose modal accessible from Settings → About → "Open source licenses ›" row. Displays all 121 runtime dependencies from the bundled JSON report, plus 6 curated entries (5 fonts, 1 vendored grammar). Each entry is tappable to view full license text.

**Implementation:**
- **Screen file:** `app/src/main/java/com/pilcrowmd/ui/components/LicensesScreen.kt` (native Compose, zero WebView)
- **JSON source:** `app/src/main/assets/open_source_licenses.json` (bundled by license plugin task dependency: `licenseDebugReport → mergeDebugAssets`, `licenseReleaseReport → mergeReleaseAssets`)
- **Bundled license texts:** `app/src/main/assets/licenses/{OFL-1.1,Apache-2.0,MIT,LGPL-2.1,GPL-2.0-Classpath-Exception}.txt`
- **Curated entries (always shown, never skipped):**
  - 5 fonts (all OFL-1.1): Source Serif 4, JetBrains Mono, IBM Plex Mono, Merriweather, Atkinson Hyperlegible
  - 1 grammar (MIT): VSCode Markdown TextMate Grammar
- **Special notes displayed in detail view:**
  - **jlatexmath:** "Note: This library is licensed under GPLv2 with the Classpath Exception, which permits linking into other applications. See the full license text for details."
  - **Sora Editor:** "Note: Sora Editor is licensed under LGPL-2.1 and used as an unmodified binary dependency. The app's full source code and the exact dependency version are published, so users may rebuild the app with a modified editor if they wish."

**Graceful degradation (never crashes):**
- If JSON parsing fails: show curated entries only, skip JSON deps, never crash.
- If a license-text file is missing/malformed: show "License text not available — see LICENSES.md" instead of crashing.

**Navigation:**
- Main screen → Settings (modal, independent from other modals via `rememberSaveable`)
- Settings → expand About card → tap "Open source licenses ›" row
- Opens LicensesScreen (full-screen modal, replaces Settings)
- Tap dependency row → shows license-text detail
- Back button → returns to list
- Close button → returns to main screen / Welcome screen

**Design:**
- Token-driven colors only (`PilcrowColors.*`); no hardcoded hex
- Matches SettingsScreen styling (border, rounded corners, padding)
- LazyColumn for efficient scrolling
- Tappable rows with ChevronRight affordance
- Responsive to the font-scale preference (inherited from theme tokens)

---

## 5. All shipped runtime dependencies (release variant)

121 dependencies, from `licenseReleaseReport.json`. Flagged (non-permissive) rows first.

| Dependency (group:artifact) | Version | SPDX | Tier |
|------------------------------|---------|------|------|
| ru.noties:jlatexmath-android | 0.2.0 | GPL-2.0-only | STRONG COPYLEFT |
| io.github.rosemoe:editor | 0.24.5 | LGPL-2.1-only | WEAK COPYLEFT |
| io.github.rosemoe:language-textmate | 0.24.5 | LGPL-2.1-only | WEAK COPYLEFT |
| org.eclipse.jdt:org.eclipse.jdt.annotation | 2.4.100 | EPL-2.0 | WEAK COPYLEFT |
| androidx.activity:activity | 1.9.3 | Apache-2.0 | PERMISSIVE |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | PERMISSIVE |
| androidx.activity:activity-ktx | 1.9.3 | Apache-2.0 | PERMISSIVE |
| androidx.annotation:annotation-experimental | 1.4.1 | Apache-2.0 | PERMISSIVE |
| androidx.annotation:annotation-jvm | 1.9.1 | Apache-2.0 | PERMISSIVE |
| androidx.appcompat:appcompat-resources | 1.6.1 | Apache-2.0 | PERMISSIVE |
| androidx.arch.core:core-common | 2.2.0 | Apache-2.0 | PERMISSIVE |
| androidx.arch.core:core-runtime | 2.2.0 | Apache-2.0 | PERMISSIVE |
| androidx.autofill:autofill | 1.0.0 | Apache-2.0 | PERMISSIVE |
| androidx.collection:collection-jvm | 1.5.0 | Apache-2.0 | PERMISSIVE |
| androidx.collection:collection-ktx | 1.5.0 | Apache-2.0 | PERMISSIVE |
| androidx.compose.animation:animation-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.animation:animation-core-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.animation:animation-core-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.animation:animation-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.foundation:foundation-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.foundation:foundation-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.foundation:foundation-layout-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.foundation:foundation-layout-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material3:material3-android | 1.3.1 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material3:material3-desktop | 1.3.1 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-icons-core-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-icons-core-desktop | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-icons-extended-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-icons-extended-desktop | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-ripple-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.material:material-ripple-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.runtime:runtime-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.runtime:runtime-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.runtime:runtime-saveable-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.runtime:runtime-saveable-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-geometry-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-geometry-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-graphics-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-graphics-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-text-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-text-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-tooling-preview-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-tooling-preview-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-unit-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-unit-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-util-android | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-util-jvmstubs | 1.7.5 | Apache-2.0 | PERMISSIVE |
| androidx.concurrent:concurrent-futures | 1.1.0 | Apache-2.0 | PERMISSIVE |
| androidx.core:core | 1.13.1 | Apache-2.0 | PERMISSIVE |
| androidx.core:core-ktx | 1.13.1 | Apache-2.0 | PERMISSIVE |
| androidx.customview:customview | 1.0.0 | Apache-2.0 | PERMISSIVE |
| androidx.customview:customview-poolingcontainer | 1.0.0 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-android | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-core-android | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-core-jvm | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-core-okio-jvm | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-jvm | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-preferences-android | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-preferences-core-jvm | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.datastore:datastore-preferences-jvm | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.emoji2:emoji2 | 1.3.0 | Apache-2.0 | PERMISSIVE |
| androidx.exifinterface:exifinterface | 1.3.7 | Apache-2.0 | PERMISSIVE |
| androidx.graphics:graphics-path | 1.0.1 | Apache-2.0 | PERMISSIVE |
| androidx.interpolator:interpolator | 1.0.0 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-common-java8 | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-common-jvm | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-livedata-core | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-process | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-runtime-android | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-runtime-compose-android | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-runtime-desktop | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-runtime-ktx-android | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-android | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-compose-android | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-compose-desktop | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-desktop | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-ktx | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.lifecycle:lifecycle-viewmodel-savedstate | 2.8.7 | Apache-2.0 | PERMISSIVE |
| androidx.profileinstaller:profileinstaller | 1.3.1 | Apache-2.0 | PERMISSIVE |
| androidx.recyclerview:recyclerview | 1.3.2 | Apache-2.0 | PERMISSIVE |
| androidx.savedstate:savedstate | 1.2.1 | Apache-2.0 | PERMISSIVE |
| androidx.savedstate:savedstate-ktx | 1.2.1 | Apache-2.0 | PERMISSIVE |
| androidx.startup:startup-runtime | 1.1.1 | Apache-2.0 | PERMISSIVE |
| androidx.tracing:tracing | 1.0.0 | Apache-2.0 | PERMISSIVE |
| androidx.vectordrawable:vectordrawable | 1.1.0 | Apache-2.0 | PERMISSIVE |
| androidx.vectordrawable:vectordrawable-animated | 1.1.0 | Apache-2.0 | PERMISSIVE |
| androidx.versionedparcelable:versionedparcelable | 1.1.1 | Apache-2.0 | PERMISSIVE |
| com.atlassian.commonmark:commonmark | 0.13.0 | BSD-2-Clause | PERMISSIVE |
| com.atlassian.commonmark:commonmark-ext-gfm-strikethrough | 0.13.0 | BSD-2-Clause | PERMISSIVE |
| com.atlassian.commonmark:commonmark-ext-gfm-tables | 0.13.0 | BSD-2-Clause | PERMISSIVE |
| com.google.code.gson:gson | 2.13.2 | Apache-2.0 | PERMISSIVE |
| com.google.errorprone:error_prone_annotations | 2.41.0 | Apache-2.0 | PERMISSIVE |
| com.google.guava:listenablefuture | 1.0 | Apache-2.0 | PERMISSIVE |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 | PERMISSIVE |
| com.squareup.okio:okio-jvm | 3.9.0 | Apache-2.0 | PERMISSIVE |
| io.coil-kt:coil | 2.7.0 | Apache-2.0 | PERMISSIVE |
| io.coil-kt:coil-base | 2.7.0 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:core | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:ext-latex | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:ext-strikethrough | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:ext-tables | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:ext-tasklist | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:html | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:inline-parser | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:linkify | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:recycler | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties.markwon:syntax-highlight | 4.6.2 | Apache-2.0 | PERMISSIVE |
| io.noties:prism4j | 2.0.0 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin:kotlin-android-extensions-runtime | 1.9.22 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin:kotlin-parcelize-runtime | 1.9.22 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin:kotlin-stdlib | 2.3.10 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7 | 1.8.21 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8 | 1.8.21 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm | 1.8.1 | Apache-2.0 | PERMISSIVE |
| org.jetbrains:annotations | 23.0.0 | Apache-2.0 | PERMISSIVE |
| org.jruby.jcodings:jcodings | 1.0.64 | MIT | PERMISSIVE |
| org.jruby.joni:joni | 2.2.7 | MIT | PERMISSIVE |
| org.snakeyaml:snakeyaml-engine | 3.0.1 | Apache-2.0 | PERMISSIVE |

> Note on the `*-jvmstubs` / `*-desktop` Compose entries: these are Compose-multiplatform stub coordinates that the BOM resolves; the Android `*-android` variants are what actually execute on device. All are Apache-2.0 regardless.

---

## 6. Build-time-only components (NOT distributed — completeness only)

These are Gradle plugins, annotation processors, and test/debug dependencies. They are **not packaged into the release APK**, so they impose **no distribution obligation** on the shipped app. Licenses below are from declared metadata / general knowledge (the release report does not cover them by design).

| Component | Version | Scope | License (approx.) | Tier (informational) |
|-----------|---------|-------|-------------------|----------------------|
| com.android.application (AGP) | 8.7.3 | Gradle plugin | Apache-2.0 | PERMISSIVE |
| org.jetbrains.kotlin (android/compose) | 2.3.10 | Gradle plugin | Apache-2.0 | PERMISSIVE |
| org.jlleitschuh.gradle.ktlint | 14.2.0 | Gradle plugin | MIT | PERMISSIVE |
| io.gitlab.arturbosch.detekt | 1.23.8 | Gradle plugin | Apache-2.0 | PERMISSIVE |
| io.github.takahirom.roborazzi (plugin) | 1.63.0 | Gradle plugin | Apache-2.0 | PERMISSIVE |
| com.jaredsburrows.license | 0.9.8 | Gradle plugin (this tooling) | Apache-2.0 | PERMISSIVE |
| io.noties:prism4j-bundler | 2.0.0 | kapt (annotation processor) | Apache-2.0 | PERMISSIVE |
| com.squareup.leakcanary:leakcanary-android | 2.14 | debugImplementation | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-tooling | (BOM 2024.10.01) | debugImplementation | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-test-manifest | (BOM 2024.10.01) | debugImplementation | Apache-2.0 | PERMISSIVE |
| junit:junit | 4.13.2 | testImplementation | EPL-1.0 | WEAK COPYLEFT (test-only, not shipped) |
| org.robolectric:robolectric | 4.16.1 | testImplementation | MIT | PERMISSIVE |
| io.github.takahirom.roborazzi:roborazzi(-compose) | 1.63.0 | testImplementation | Apache-2.0 | PERMISSIVE |
| androidx.compose.ui:ui-test-junit4 | (BOM 2024.10.01) | testImplementation | Apache-2.0 | PERMISSIVE |
| androidx.test:runner | 1.5.0 | androidTestImplementation | Apache-2.0 | PERMISSIVE |
| androidx.test:rules | 1.5.0 | androidTestImplementation | Apache-2.0 | PERMISSIVE |

The only non-permissive build-time item is **JUnit 4 (EPL-1.0)** — test scope, never in the APK, so not distribution-relevant.

---

## 7. How to regenerate

```bash
./gradlew :app:licenseReleaseReport
# Outputs: app/build/reports/licenses/licenseReleaseReport.{json,csv,txt}
```

The plugin's default "copy HTML report into `src/main/assets/`" behavior is **disabled** in `app/build.gradle.kts` (`copyHtmlReportToAssets = false`) so the report is never bundled into the shipped APK. Fonts and vendored source (§3–§4) are manual and must be re-checked when font files or `assets/` change.
