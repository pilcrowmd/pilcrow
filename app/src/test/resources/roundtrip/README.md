# Round-trip corpus fixtures

Test-only inputs for `com.pilcrowmd.roundtrip.RoundTripCorpusTest`: open a file, save it unchanged,
and the bytes on disk must be identical to the original. Every file here is committed **verbatim** –
byte-for-byte as downloaded, not edited, reflowed or re-encoded. That is the point of the test.

Everything else in the corpus is generated at test time by `RoundTripCorpus.kt` and
`UnicodeSamples.kt` (line-ending, BOM and no-final-newline variants of these files, the
individual spec examples, RTL/emoji/combining-character documents, huge tables, mixed endings and
the non-UTF-8 samples), so none of it is committed.

## Sources and licences

| File | Source | Revision | Licence |
|------|--------|----------|---------|
| `commonmark-spec-0.31.2.txt` | `spec.txt` from github.com/commonmark/commonmark-spec | tag `0.31.2` (`9103e341a973`) | CC BY-SA 4.0 |
| `gfm-spec-0.29.txt` | `test/spec.txt` from github.com/github/cmark-gfm | `499789b49373` | CC BY-SA 4.0 |
| `readmes/bat.md` | `README.md` from github.com/sharkdp/bat | `4987f76709aa` | MIT OR Apache-2.0 |
| `readmes/fzf.md` | `README.md` from github.com/junegunn/fzf | `b1be3a8be1b8` | MIT |
| `readmes/hyperfine.md` | `README.md` from github.com/sharkdp/hyperfine | `f12f3d9f86f3` | MIT OR Apache-2.0 |
| `readmes/lazygit.md` | `README.md` from github.com/jesseduffield/lazygit | `cfbbf18656c2` | MIT |
| `readmes/pandoc.md` | `README.md` from github.com/jgm/pandoc | `663f89eb66c1` | GPL-2.0-or-later |
| `readmes/ripgrep.md` | `README.md` from github.com/BurntSushi/ripgrep | `3fce3b5bb023` | Unlicense OR MIT |

All are compatible with this project's GPL-3.0-or-later: CC BY-SA 4.0 is listed by Creative Commons
as one-way compatible with GPLv3; MIT, Apache-2.0 and the Unlicense are GPLv3-compatible permissive
licences; GPL-2.0-or-later may be used under GPLv3. Where a file is dual-licensed it is used here
under its MIT option. None of the files has been modified.

### Attribution

- **CommonMark Spec**, version 0.31.2, copyright John MacFarlane,
  <https://spec.commonmark.org/0.31.2/>, licensed under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- **GitHub Flavored Markdown Spec**, version 0.29-gfm, a derivative of the CommonMark Spec by John
  MacFarlane, <https://github.github.com/gfm/>, licensed under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- `bat.md`: Copyright (c) 2018-2025 bat-developers (https://github.com/sharkdp/bat).
- `fzf.md`: Copyright (c) 2013-2026 Junegunn Choi.
- `hyperfine.md`: Copyright (c) 2018-2022 David Peter, and all hyperfine contributors.
- `lazygit.md`: Copyright (c) 2018 Jesse Duffield.
- `pandoc.md`: Copyright (C) 2006-2024 John MacFarlane. Licensed under the GNU General Public
  License, version 2 or (at your option) any later version.
- `ripgrep.md`: Copyright (c) 2015 Andrew Gallant.

The MIT licence under which `bat.md`, `fzf.md`, `hyperfine.md`, `lazygit.md` and `ripgrep.md` are
used:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
> OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
