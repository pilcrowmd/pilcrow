# Security Policy

## Supported versions

| Version       | Supported          |
| ------------- | ------------------ |
| 1.x (latest)  | ✅ Security fixes  |
| Older         | ❌ Please update   |

The latest release receives security fixes. Please update before reporting an issue if you can.

---

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.** Use GitHub's private
vulnerability reporting:

1. Go to the repository's **Security** tab → **Report a vulnerability**
   ([direct link](https://github.com/pilcrowmd/pilcrow/security/advisories/new)).
2. Include as much as you can:
   - affected version(s),
   - device model and Android version,
   - clear reproduction steps (and a sample Markdown file if relevant),
   - the impact (what an attacker could do).

We aim to acknowledge reports within a few days and will keep you updated as we investigate. If you
are unable to use GitHub's reporter, email **pilcrowmd@gmail.com** and we will arrange another
confidential channel.

---

## Security posture (privacy by design)

Pilcrow is a local, offline-first reader. Its design removes whole classes of risk:

- **No WebView, no JavaScript.** Markdown is rendered to native Android views. There is no embedded
  browser or JS runtime, which eliminates web-style vulnerabilities (XSS, CSRF, and similar).
- **Scoped file access.** Files are opened and saved through Android's Storage Access Framework with
  user-granted, scoped URI permissions — Pilcrow does not request broad device-storage access.
- **No accounts, analytics, ads, or telemetry.** The app bundles no analytics or crash-reporting
  SDKs and does not phone home.
- **Offline reading path.** With default settings, reading and rendering make no network requests.
  Markdown image references are not fetched over the network — they render as alt text — so opening a
  document cannot trigger outbound connections.
- **Content integrity.** Saves are atomic (a failed save aborts cleanly and never leaves a partial
  or corrupted file), and content is preserved byte-for-byte on round trip (line endings and
  frontmatter are not silently rewritten).

### The one optional network feature

Cloud diagram (Mermaid) rendering is the **only** feature that uses the network, and it is **off by
default**. When a user explicitly enables it in Settings, diagram source text is sent to a
third-party rendering service to produce an image; if that request fails, the diagram degrades to a
local code block. With the setting off (the default), everything renders locally and the reading
path stays fully offline.

---

## Out of scope

The following generally fall outside the threat model (though you're still welcome to report them):

- Issues that require a **rooted or already-compromised device**, or **physical access to an unlocked
  device**.
- **Social engineering** that tricks a user into opening untrusted files or granting permissions.
- **Third-party dependency CVEs without a demonstrated, realistic exploit path through Pilcrow.** We
  still track and update dependencies — please report these so we can assess them.
- **Resource exhaustion** from pathologically large files (these are a performance limit, not a
  corruption or code-execution risk).

---

## Disclosure policy

We follow **coordinated disclosure**: report privately, we assess and fix, we release a patched
version, and we publish an advisory crediting you (unless you prefer to remain anonymous). Please
hold public discussion until a fix is released so users can update first.

---

## See also

- [LICENSES.md](LICENSES.md) — third-party dependencies and their licenses.
- [CONTRIBUTING.md](CONTRIBUTING.md) — development standards and the quality gate.
