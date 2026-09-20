package com.d8gp.plugins.protocol.substrate

import kotlinx.serialization.json.JsonPrimitive

// The sandboxed plugin kernel: the shell document + kernel, and the per-plugin
// iframe bridge. Both are plain JS strings evaluated inside whatever engine the
// host provides — an Android WebView, or a Chromium tab driven over CDP.
//
// Topology: one shell document (top frame) hosts the KERNEL, which creates one
// `<iframe sandbox="allow-scripts">` per plugin. Each iframe runs the BRIDGE,
// which exposes window.__d8gp to the plugin bundle and relays RPC to the
// kernel, which relays to the host process and back.
//
// Transport neutrality is what lets this source be shared: the kernel talks to
// whichever host global exists — `window.__d8gpHost` (the Android host injects
// it via addJavascriptInterface; the CDP host installs a shim over its binding)
// or `window.ReactNativeWebView` (the RN host).
//
// The iframe is opaque-origin (allow-scripts, no allow-same-origin) and the
// shell CSP `default-src 'none'` is inherited by the srcdoc frame, so plugin
// code has no direct network — every byte flows through the permission-gated
// RPC. CSP behavior varies by engine; it is the backstop, not the primary
// control (that is hostAllowed at the capability layer).
//
// HARD CONSTRAINT: the literal script-close tag sequence must never appear in
// KERNEL or IFRAME_BRIDGE (they are inlined into <script> elements, and the
// HTML parser ends a script element at the FIRST close sequence anywhere in
// its text, even inside a JS string or comment). KernelShellTest pins this.

/**
 * Runs INSIDE each sandboxed iframe. Self-contained (no closure refs) so it can
 * be stringified into srcdoc.
 */
val IFRAME_BRIDGE: String = """
function __d8gpBridge(PLUGIN_ID, MANIFEST) {
  var parentWin = window.parent;
  function post(o){ parentWin.postMessage(o, '*'); }
  var pending = {}, counter = 0, handlers = {}, booted = false;
  function fatal(e){ post({ d8gp:'error', error: String(e && (e.stack || e.message) || e) }); }
  // Async failures (setup() rejection, uncaught error mid-fetch) must reach the
  // crash machine too — the boot try/catch only sees synchronous throws.
  window.addEventListener('error', function(ev){ fatal(ev.error || ev.message); });
  window.addEventListener('unhandledrejection', function(ev){ fatal(ev.reason); });
  function req(id, method, params){ return JSON.stringify({v:1,t:'req',id:id,plugin:PLUGIN_ID,method:method,params:params}); }
  function evt(event, payload){ return JSON.stringify({v:1,t:'event',plugin:PLUGIN_ID,event:event,payload:payload}); }
  window.__d8gp = {
    manifest: MANIFEST,
    call: function(method, params, opts){
      return new Promise(function(resolve, reject){
        var id = 'p:' + (counter++);
        var ms = (opts && opts.timeoutMs) || 30000;
        var timer = ms > 0 ? setTimeout(function(){ delete pending[id]; reject(new Error(method + ' timed out')); }, ms) : null;
        pending[id] = { resolve: resolve, reject: reject, timer: timer };
        post({ d8gp: 'rpc', data: req(id, method, params) });
      });
    },
    emit: function(event, payload){ post({ d8gp: 'rpc', data: evt(event, payload) }); },
    setHandler: function(method, fn){ handlers[method] = fn; }
  };
  function res(id, ok, value){
    var m = ok
      ? { v:1, t:'res', id:id, plugin:PLUGIN_ID, ok:true, result: value === undefined ? null : value }
      : { v:1, t:'res', id:id, plugin:PLUGIN_ID, ok:false, error: { code:'plugin-error', message: String(value && value.message || value) } };
    post({ d8gp:'rpc', data: JSON.stringify(m) });
  }
  window.addEventListener('message', function(ev){
    // Only the kernel may talk to this frame — sibling plugin iframes can reach
    // us via parent.frames[n].postMessage and must not inject boot/rpc frames.
    if (ev.source !== parentWin) return;
    var data = ev.data;
    if (!data || !data.d8gp) return;
    if (data.d8gp === 'boot'){
      if (booted) return;
      booted = true;
      try { (0, eval)(data.bundle); } catch(e){ fatal(e); }
      return;
    }
    if (data.d8gp === 'rpc'){
      var msg; try { msg = JSON.parse(data.data); } catch(e){ return; }
      if (msg.t === 'req'){
        var fn = handlers[msg.method];
        if (!fn){ res(msg.id, false, 'no handler for ' + msg.method); return; }
        try { Promise.resolve(fn(msg.params)).then(function(r){ res(msg.id, true, r); }, function(e){ res(msg.id, false, e); }); }
        catch(e){ res(msg.id, false, e); }
      } else if (msg.t === 'res'){
        var p = pending[msg.id]; if (!p) return; delete pending[msg.id];
        if (p.timer) clearTimeout(p.timer);
        if (msg.ok) p.resolve(msg.result); else p.reject(new Error(msg.error && msg.error.message || 'error'));
      }
    }
  });
  post({ d8gp: 'ready' });
}
"""

// The top-frame kernel, split around the point where the bridge source is
// embedded as a JS string literal.
private val KERNEL_HEAD = """
(function(){
  var HOST = window.__d8gpHost || window.ReactNativeWebView;
  function toHost(o){ if (HOST) HOST.postMessage(JSON.stringify(o)); }
  var iframes = {}, pendingBundle = {};
  function srcdoc(pluginId, manifestJson){
    // <-escape the manifest JSON so a string field containing a
    // script-close tag can't break out of the inline script (JSON semantics
    // unchanged). NB: this comment is INSIDE the kernel source string — the
    // literal close-tag sequence must never appear anywhere in it.
    var safeManifest = manifestJson.replace(/</g, '\\u003c');
    return '<!DOCTYPE html><html><head><meta charset="utf-8">' +
      '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\' \'unsafe-eval\'">' +
      '</head><body><script>' + """

private val KERNEL_TAIL = """ +
      '\n__d8gpBridge(' + JSON.stringify(pluginId) + ',' + safeManifest + ');' +
      '<\/script></body></html>';
  }
  window.__kernel = {
    boot: function(pluginId, bundle, manifestJson){
      try {
        // Re-boot after a crash must not leak the dead frame.
        var old = iframes[pluginId];
        if (old && old.parentNode) old.parentNode.removeChild(old);
        delete iframes[pluginId];
        pendingBundle[pluginId] = bundle;
        var f = document.createElement('iframe');
        f.setAttribute('sandbox', 'allow-scripts');
        f.style.display = 'none';
        f.srcdoc = srcdoc(pluginId, manifestJson);
        iframes[pluginId] = f;
        document.body.appendChild(f);
      } catch(e){ toHost({ t:'crash', plugin: pluginId, error: String(e) }); }
    },
    evict: function(pluginId){
      var f = iframes[pluginId];
      if (f && f.parentNode) f.parentNode.removeChild(f);
      delete iframes[pluginId]; delete pendingBundle[pluginId];
    },
    rpc: function(json){
      try {
        var msg = JSON.parse(json);
        var f = iframes[msg.plugin];
        if (f && f.contentWindow) f.contentWindow.postMessage({ d8gp:'rpc', data: json }, '*');
      } catch(e){}
    },
    ping: function(nonce){ toHost({ t:'pong', nonce: nonce }); }
  };
  window.addEventListener('message', function(ev){
    var data = ev.data;
    if (!data || !data.d8gp) return;
    var pluginId = null;
    for (var id in iframes){ if (iframes[id].contentWindow === ev.source){ pluginId = id; break; } }
    if (pluginId === null) return;
    if (data.d8gp === 'ready'){
      var b = pendingBundle[pluginId]; delete pendingBundle[pluginId];
      var f = iframes[pluginId];
      if (f && f.contentWindow) f.contentWindow.postMessage({ d8gp:'boot', bundle: b }, '*');
      toHost({ t:'ready', plugin: pluginId });
    } else if (data.d8gp === 'rpc'){
      toHost({ t:'rpc', plugin: pluginId, data: data.data });
    } else if (data.d8gp === 'error'){
      toHost({ t:'crash', plugin: pluginId, error: String(data.error) });
    }
  });
  toHost({ t: 'shell-ready' });
})();
"""

val KERNEL: String = KERNEL_HEAD + jsStringLiteral(IFRAME_BRIDGE) + KERNEL_TAIL

// Assembled from halves so this source file never contains the sequence.
private val SCRIPT_CLOSE = "</scr" + "ipt>"

val SHELL_HTML: String = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; frame-src 'self'">
<style>html,body{margin:0;padding:0;background:#000}</style>
</head><body><script>""" + KERNEL + SCRIPT_CLOSE + "</body></html>"

/**
 * A JSON string literal is also a valid JS string literal for our sources, so
 * this doubles as the JSON.stringify the RN host uses for JS injection.
 */
fun jsStringLiteral(value: String): String = JsonPrimitive(value).toString()
