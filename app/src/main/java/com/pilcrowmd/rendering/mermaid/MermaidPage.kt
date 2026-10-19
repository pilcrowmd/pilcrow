// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

/**
 * The page the hidden WebView compiles diagrams in (M-70). It loads one script, the bundled
 * Mermaid, and defines `pilcrowRender(id, source, cfg)`, which draws a diagram to a PNG and leaves
 * the outcome in `window.__pilcrow[id]` for the app to collect with `evaluateJavascript`. Nothing
 * on the page can call into the app: there is no JavaScript interface (security lock 2).
 *
 * The Content-Security-Policy is the second wall behind the WebViewClient's: no request leaves the
 * page except for the one script URL, and pictures only come from `data:`/`blob:`.
 */
internal object MermaidPage {
    /** A virtual origin: never fetched from the network, every request is answered locally. */
    const val BASE_URL = "https://appassets.androidplatform.net/"

    /**
     * Where the page itself is loaded from; the WebViewClient answers it with [html]. Not
     * `loadDataWithBaseURL`: WebView routes that document through `shouldInterceptRequest` as a
     * main-frame `data:` request, which the client's 404 would turn into an error page.
     */
    const val PAGE_URL = BASE_URL + "index.html"

    /** The page's one script; the WebViewClient answers it from [SCRIPT_ASSET]. */
    const val SCRIPT_URL = BASE_URL + "mermaid/mermaid.min.js"

    /** Generated at build time by app/build.gradle.kts, section "Offline Mermaid" (SHA-256 pinned). */
    const val SCRIPT_ASSET = "mermaid/mermaid.min.js"

    /** The tallest picture drawn, in pixels; a taller diagram is scaled down to fit. */
    const val MAX_HEIGHT_PX = 4096

    /** Nothing loads but the one script; pictures only from `data:` and `blob:`. */
    private const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; script-src https://appassets.androidplatform.net 'unsafe-inline'; " +
            "style-src 'unsafe-inline'; img-src data: blob:; font-src data:"

    /** Answers "ready", "failed" (the script did not load) or "wait". */
    const val READY_PROBE =
        "(typeof mermaid !== 'undefined' && typeof pilcrowRender === 'function') ? 'ready' : " +
            "(window.__pilcrowLoadFailed ? 'failed' : 'wait')"

    fun pollScript(id: Int) = "JSON.stringify(window.__pilcrow[$id] || null)"

    fun forgetScript(id: Int) = "delete window.__pilcrow[$id];"

    // The body has a fixed CSS width because some diagram types (gantt) size themselves to their
    // container, and a WebView that is never attached to a window has no viewport width.
    val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$CONTENT_SECURITY_POLICY">
<style>body { margin: 0; width: 1200px; }</style>
<script src="$SCRIPT_URL" onerror="window.__pilcrowLoadFailed = true"></script>
<script>
window.__pilcrow = {};
window.__pilcrowCurrent = -1;

function pilcrowRemove(elementId) {
  var node = document.getElementById(elementId);
  if (node) node.remove();
}

// UTF-8 safe base64: btoa alone only takes Latin-1.
function pilcrowBase64(text) {
  var bytes = new TextEncoder().encode(text);
  var binary = '';
  for (var i = 0; i < bytes.length; i += 32768) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 32768));
  }
  return btoa(binary);
}

// SVG text to a transparent PNG exactly `width` pixels wide. The diagram is never upscaled past the
// screen density: scale = min(density, width / natural width), reduced further so the height stays
// within the cap, and the drawing is centred horizontally.
function pilcrowRasterise(svgText, width, density) {
  return new Promise(function (resolve, reject) {
    // An inert document: nothing in it loads or runs while the SVG is inspected.
    var inert = document.implementation.createHTMLDocument('');
    inert.body.innerHTML = svgText;
    var svg = inert.body.querySelector('svg');
    if (!svg) { reject(new Error('no svg')); return; }
    var box = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
    if (box.length !== 4 || !(box[2] > 0) || !(box[3] > 0)) { reject(new Error('no viewBox')); return; }
    var scale = Math.min(density > 0 ? density : 1, width / box[2]);
    if (box[3] * scale > $MAX_HEIGHT_PX) scale = $MAX_HEIGHT_PX / box[3];
    var dw = Math.max(1, Math.round(box[2] * scale));
    var dh = Math.max(1, Math.round(box[3] * scale));
    var x = Math.floor((width - dw) / 2);
    svg.removeAttribute('style');
    svg.setAttribute('width', String(dw));
    svg.setAttribute('height', String(dh));
    var img = new Image();
    img.onload = function () {
      try {
        var canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = dh;
        canvas.getContext('2d').drawImage(img, x, 0, dw, dh);
        resolve({ png: canvas.toDataURL('image/png').split(',')[1], w: width, h: dh });
      } catch (e) {
        reject(e); // a tainted canvas throws SecurityError here
      }
    };
    img.onerror = function () { reject(new Error('svg image failed to load')); };
    img.src = 'data:image/svg+xml;base64,' + pilcrowBase64(new XMLSerializer().serializeToString(svg));
  });
}

window.pilcrowRender = function (id, source, cfg) {
  // Anything a previous, abandoned render left in the page goes first.
  document.querySelectorAll('body > :not(script)').forEach(function (node) { node.remove(); });
  window.__pilcrow = {};
  window.__pilcrowCurrent = id;
  var svgId = 'd' + id;
  var vars = {
    background: cfg.background,
    primaryTextColor: cfg.text,
    textColor: cfg.text,
    titleColor: cfg.text,
    pieTitleTextColor: cfg.text,
    pieLegendTextColor: cfg.text,
    pieSectionTextColor: cfg.text,
    lineColor: cfg.line
  };
  if (cfg.border) vars.primaryBorderColor = cfg.border;
  // Own slice colours (M-192), repeated past the end of the list; their labels take the panel colour.
  if (cfg.slices && cfg.slices.length) {
    for (var i = 0; i < 12; i++) vars['pie' + (i + 1)] = cfg.slices[i % cfg.slices.length];
    vars.pieSectionTextColor = cfg.background;
  }
  function settle(outcome) {
    // An abandoned render finishing late must not write over the current one.
    if (window.__pilcrowCurrent === id) window.__pilcrow[id] = outcome;
  }
  (async function () {
    try {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: cfg.dark ? 'dark' : 'default',
        themeVariables: vars,
        // SVG text, not HTML in foreignObject: foreignObject would taint the canvas.
        htmlLabels: false,
        flowchart: { htmlLabels: false },
        fontFamily: 'Roboto, "Noto Sans", "Helvetica Neue", Arial, sans-serif'
        // maxTextSize stays at Mermaid's default.
      });
      await mermaid.parse(source);
      var out = await mermaid.render(svgId, source);
      var picture = await pilcrowRasterise(out.svg, cfg.width, cfg.density);
      settle({ state: 'ok', png: picture.png, w: picture.w, h: picture.h });
    } catch (e) {
      settle({ state: 'error', msg: String((e && e.message) || e).slice(0, 200) });
    } finally {
      pilcrowRemove(svgId);
      pilcrowRemove('d' + svgId);
      pilcrowRemove('i' + svgId);
    }
  })();
};
</script>
</head>
<body></body>
</html>
    """.trimIndent()
}
