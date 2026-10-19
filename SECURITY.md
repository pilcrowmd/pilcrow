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

- **No internet permission.** The app does not hold Android's `INTERNET` permission, so it cannot
  connect to the internet at all: Android itself refuses any connection from it. Nothing you open
  leaves the phone.
- **Native Markdown rendering.** Markdown is rendered to native Android views, not HTML in a browser.
  The one exception is a hidden, sandboxed WebView that only turns Mermaid diagrams into pictures
  (see below).
- **Scoped file access.** Files are opened and saved through Android's Storage Access Framework with
  user-granted, scoped URI permissions – Pilcrow does not request broad device-storage access.
- **No accounts, analytics, ads, or telemetry.** The app bundles no analytics or crash-reporting
  SDKs and does not phone home.
- **Offline reading path.** Reading and rendering make no network requests. Web image references are
  never fetched; pictures stored with the note are read from the device only. Opening a document
  cannot trigger outbound connections.
- **Content integrity.** Saves are atomic (a failed save aborts cleanly and never leaves a partial
  or corrupted file), and content is preserved byte-for-byte on round trip (line endings and
  frontmatter are not silently rewritten).

### The one WebView: Mermaid diagrams

Mermaid diagrams are drawn on the phone, offline, by the official Mermaid library (mermaid.js
11.17.2, MIT licence) bundled inside the app. It runs in a hidden WebView (Android's built-in
browser component) that is used only to turn the diagram text into a picture. The WebView is never
shown on screen and never attached to a window; the picture is shown in the normal native reader. An
earlier opt-in setting that sent diagram source to a third-party service (mermaid.ink) is removed.

What the WebView can do: run the bundled Mermaid script on the diagram text from the open document,
and return a picture. Nothing else. What it cannot do:

- **No network.** Network loads are blocked in its settings, every request other than the drawing
  page and its bundled Mermaid script (both served from inside the app) is refused, and the app has no `INTERNET` permission.
- **No file or content access.** File and content access are switched off: no `content://` URIs, no
  other documents, no app storage.
- **No JavaScript bridge.** The page cannot call any app code; the app only reads back the finished
  picture.
- **No navigation and no pop-up windows.**
- **Strict mode.** Mermaid runs in its "strict" security mode (labels sanitised, click handlers off).

Each diagram has an 8-second time limit. A diagram that is broken, unsupported, too slow, or that
makes the engine fail is shown as its source in a code block; the app never crashes on a diagram. If
the device's WebView is missing or disabled, every diagram shows as source.

The Mermaid script is not stored in the repository: the build downloads the exact npm package
`mermaid@11.17.2` and refuses it unless its SHA-256 matches a pinned value. Details:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#mermaid-diagrams-the-one-webview).

---

## Out of scope

The following generally fall outside the threat model (though you're still welcome to report them):

- Issues that require a **rooted or already-compromised device**, or **physical access to an unlocked
  device**.
- **Social engineering** that tricks a user into opening untrusted files or granting permissions.
- **Third-party dependency CVEs without a demonstrated, realistic exploit path through Pilcrow.** We
  still track and update dependencies – please report these so we can assess them.
- **Resource exhaustion** from pathologically large files (these are a performance limit, not a
  corruption or code-execution risk).

---

## Disclosure policy

We follow **coordinated disclosure**: report privately, we assess and fix, we release a patched
version, and we publish an advisory crediting you (unless you prefer to remain anonymous). Please
hold public discussion until a fix is released so users can update first.

---

## See also

- [LICENSES.md](LICENSES.md) – third-party dependencies and their licenses.
- [CONTRIBUTING.md](CONTRIBUTING.md) – development standards and the quality gate.
