# PilcrowMD test packs

Markdown files you can download and open in PilcrowMD to see how it handles your kind of document,
on your own phone. Each pack is one file: open it, scroll, and compare with what the header at the
top of the file says to look for. Each header also lists the **known gaps** – things we already
know are not right yet – so you can tell a known gap from something new.

| Pack | File | Size | What it covers |
| ---- | ---- | ---: | -------------- |
| General | [pilcrow-test-general.md](pilcrow-test-general.md) | 7 KB | Everything at a glance: headings, lists, tasks, callouts, a table, code, maths, footnotes, a collapsible section, many scripts and some deliberately broken input. |
| Coders | [pilcrow-test-coders.md](pilcrow-test-coders.md) | 10 KB | Code blocks in the languages that are coloured, including those new in version 1.0.12, diffs and very long lines. |
| Admins | [pilcrow-test-admins.md](pilcrow-test-admins.md) | 6 KB | A runbook: front matter, YAML, TOML, INI, Dockerfile, nginx, systemd, shell, logs and CSV. |
| Maths & Science | [pilcrow-test-math-science.md](pilcrow-test-math-science.md) | 5 KB | Formulas from school maths to physics: fractions, calculus, matrices, cases, statistics, Greek letters and chemistry. |
| Writers | [pilcrow-test-writers.md](pilcrow-test-writers.md) | 11 KB | A long story with callouts, footnotes, wide tables, collapsible sections, links and quotes. |
| Big file | [pilcrow-test-big-file.md](pilcrow-test-big-file.md) | 2 MB | 1,508 chapters of plain Markdown, for opening speed, scrolling, search and memory. |
| Old encoding | [pilcrow-test-old-encoding.md](pilcrow-test-old-encoding.md) | 1 KB | Part of General: a file saved in Latin-1 instead of UTF-8, to check it opens with a notice and is never overwritten. |
| Showcase | [showcase/](showcase/README.md) | 9 files | Not a test: nine realistic example documents, one per field – an assistant answer, a project README, a runbook, a product spec, a quarterly summary, contract clauses, a lab note, lecture notes and a story chapter. |

**How to download on a phone.** Open a file above, then tap **Raw** (or the download button) and
save it. Open the saved file with PilcrowMD from your Files app, or from inside PilcrowMD with
**Open**.

**Found a problem that is not in the file's known-gaps list?** Please
[open an issue](https://github.com/pilcrowmd/pilcrow/issues) and say which pack and which section,
and which phone and Android version you used.

The known-gaps lists describe the version named in each file's header. They are updated when a new
version ships.

## Licence

All files in this folder were written for PilcrowMD and are dedicated to the public domain under
[CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/). You may copy, change and
share them for any purpose, without asking and without credit. This applies only to this folder;
the app's source code is licensed under the GPL (see [LICENSE](../../LICENSE)). The licence
covers the showcase files in [showcase/](showcase/README.md) too.
