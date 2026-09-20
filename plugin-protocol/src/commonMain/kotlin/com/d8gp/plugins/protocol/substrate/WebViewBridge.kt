package com.d8gp.plugins.protocol.substrate

// The `window.__d8gp` bridge injected into a site-substrate plugin's page, so
// the SAME @sdk `definePlugin`/`getBridge` a sandboxed bundle uses runs
// unchanged as a content script. It is the site-substrate analogue of
// [IFRAME_BRIDGE], with two deliberate differences:
//
//  1. Transport is the host-injected `window.__d8gpHost` object instead of the
//     parent window — there is no kernel frame; the content script IS the top
//     frame of the real site (e.g. web.whatsapp.com). Host→plugin frames arrive
//     by the host calling `window.__d8gp.__recv(json)`.
//     TRUST BOUNDARY: in a main-world injection (Android WebView, and the CDP
//     host when a plugin needs the page's own module graph) __d8gpHost/__d8gp
//     are ordinary globals SHARED with the site's first-party scripts. The
//     transport is scoped to — and trusts — the whole site origin, NOT only
//     this bundle, which is strictly weaker than the sandboxed kernel's
//     network-blocked synthetic origin. A same-origin XSS on the site could
//     reach the host RPC surface. We pin __d8gp as non-configurable so a late
//     script cannot REPLACE the bridge, but this is a documented substrate
//     limitation, not a hard boundary. A CDP host that can use an isolated
//     world should, and then this note does not apply.
//  2. It installs NO global error / unhandledrejection → crash reporter. The
//     content script shares its JS world with the host site's own app, which
//     throws routinely; treating those as plugin crashes would crash-loop the
//     plugin on the site's noise. Boot failure instead surfaces as the plugin
//     never emitting `plugin.ready` (the runtime's ready-timeout handles it).
//
// Wire-identical to IFRAME_BRIDGE otherwise: the same {v:1,t:'req'|'res'|'event'}
// JSON frames, the same PluginBridge shape {manifest, call, emit, setHandler}.
// Idempotent: re-injection on an SPA re-render is a no-op (guarded on __d8gp).
//
// Injected as JavaScript, never inlined into HTML, so the script-close-tag
// constraint that binds KernelShell.kt does not apply here.

// Split around the manifest so it can be concatenated as a JS object literal
// (JSON ⊂ JS), matching how the kernel embeds the manifest into srcdoc.
private const val BRIDGE_HEAD = """
(function(){
  if (window.__d8gp) return;
  var MANIFEST = """

private const val BRIDGE_TAIL = """;
  var PLUGIN_ID = MANIFEST.id;
  function post(frameJson){ var h = window.__d8gpHost; if (h) h.postMessage(frameJson); }
  var pending = {}, counter = 0, handlers = {};
  function req(id, method, params){ return JSON.stringify({v:1,t:'req',id:id,plugin:PLUGIN_ID,method:method,params:params}); }
  function evt(event, payload){ return JSON.stringify({v:1,t:'event',plugin:PLUGIN_ID,event:event,payload:payload}); }
  function ok(id, value){ return JSON.stringify({v:1,t:'res',id:id,plugin:PLUGIN_ID,ok:true,result: value === undefined ? null : value}); }
  function err(id, code, message){ return JSON.stringify({v:1,t:'res',id:id,plugin:PLUGIN_ID,ok:false,error:{code:code,message:String(message)}}); }
  var BRIDGE = {
    manifest: MANIFEST,
    call: function(method, params, opts){
      return new Promise(function(resolve, reject){
        var id = 'p:' + (counter++);
        var ms = (opts && opts.timeoutMs) || 30000;
        var timer = ms > 0 ? setTimeout(function(){ delete pending[id]; reject(new Error(method + ' timed out')); }, ms) : null;
        pending[id] = { resolve: resolve, reject: reject, timer: timer };
        post(req(id, method, params));
      });
    },
    emit: function(event, payload){ post(evt(event, payload)); },
    setHandler: function(method, fn){ handlers[method] = fn; },
    __recv: function(json){
      var msg; try { msg = JSON.parse(json); } catch(e){ return; }
      if (!msg || msg.v !== 1 || msg.plugin !== PLUGIN_ID) return;
      if (msg.t === 'req'){
        var fn = handlers[msg.method];
        if (!fn){ post(err(msg.id, 'not-found', 'no handler for ' + msg.method)); return; }
        try {
          Promise.resolve(fn(msg.params == null ? {} : msg.params)).then(
            function(r){ post(ok(msg.id, r)); },
            function(e){ post(err(msg.id, 'plugin-error', e && e.message || e)); }
          );
        } catch(e){ post(err(msg.id, 'plugin-error', e && e.message || e)); }
      } else if (msg.t === 'res'){
        var p = pending[msg.id]; if (!p) return; delete pending[msg.id];
        if (p.timer) clearTimeout(p.timer);
        if (msg.ok) p.resolve(msg.result); else p.reject(new Error(msg.error && msg.error.message || 'error'));
      }
    }
  };
  // Pin the bridge so a later same-origin script can't swap it out for a shim
  // that intercepts RPC. (Reading __d8gpHost is still possible — see the
  // trust-boundary note above; this only protects bridge integrity.)
  try { Object.defineProperty(window, '__d8gp', { value: BRIDGE, configurable: false, writable: false }); }
  catch (e) { window.__d8gp = BRIDGE; }
})();
"""

/**
 * Builds the document-start bridge for one plugin, embedding its manifest as a
 * JS object literal (safe: it is injected as JS, not HTML). The bundle is
 * injected separately once the page has loaded — by then __d8gp and __d8gpHost
 * both exist.
 */
fun webViewBridgeScript(manifestJson: String): String = BRIDGE_HEAD + manifestJson + BRIDGE_TAIL
