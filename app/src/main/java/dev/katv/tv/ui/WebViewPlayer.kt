package dev.katv.tv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebViewCompat
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anti-adblock defuser berdasarkan pola AdGuard scriptlets / blockads-android.
 * KUNCINYA: stub aclib jadi no-op (BUKAN dihapus) supaya detektor sandbox tidak
 * jalan tapi player tetap punya objeknya. Disuntik di document-start (sebelum
 * script halaman) via WebViewCompat, plus fallback HTML rewrite.
 *
 * CDN host player (adexchangeclear / vbcojhroxkoaf / wpnxiswpuyrfn) SENGAJA tidak
 * diblok — bundle-jw player di-load dari situ. Cuma tracker murni yang di-204.
 */
private const val DEFUSER_JS = """
(function(){
  if (window.__kbDefuser) return;
  window.__kbDefuser = 1;
  try {
    // Frame-buster EsportEx (streams.esportex.site/player): script di <head> melakukan
    // window.location.replace("http://google.com/") kalau window.self === window.top.
    // Kita netralisir location.replace untuk redirect keluar ke google.com hanya di host
    // itu, supaya halaman player tetap dimuat di top-frame WebView.
    try {
      if (location.hostname.indexOf('esportex.site') >= 0 && window.self === window.top) {
        var _replace = location.replace.bind(location);
        location.replace = function(u){
          if (String(u).indexOf('google.com') >= 0) return;
          return _replace(u);
        };
      }
    } catch(e){}
    // CELAH UTAMA PPW: bundle-jw.js memasang <object>.onerror yang menimpa
    // document.body.innerHTML dengan pesan "Remove sandbox attributes on the
    // iframe tag" setiap kali <object> gagal dimuat di WebView. Kita cegah
    // write itu — player JW lalu menginit normal dan video langsung jalan.
    var _descr = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
    if (_descr && _descr.set) Object.defineProperty(Element.prototype, 'innerHTML', {
      get: function(){ return _descr.get.call(this); },
      set: function(v){
        var s = typeof v === 'string' ? v : '';
        if (s.indexOf('Remove sandbox') >= 0 || s.indexOf('Ad Functionality Blocked') >= 0 ||
            (this.tagName === 'BODY' && s.indexOf('Mozilla/5.0') >= 0 && s.indexOf('sandbox') >= 0)) return;
        return _descr.set.call(this, v);
      }, configurable: true
    });
  } catch(e){}
  try {
    // 1. Anti-adblock state spoofing (AdGuard prevent-adblock patterns).
    window.canRunAds = true;
    window.isAdBlockActive = false;
    window.adsBlocked = false;
    window.adblock = false;
    window.google_ad_status = 1;
    window.__adblock_detected = false;
    window.adsbygoogle = window.adsbygoogle || [];
    window.adsbygoogle.loaded = true;
    window.adsbygoogle.push = function(){};
    window.showAdblockMessage = function(){};

    // 2. STUB aclib (bukan hapus) — ini kuncinya.
    window.aclib = window.aclib || {};
    window.aclib.runPop = function(){};
    window.aclib.runInPagePush = function(){};
    window.aclib.runAutoTag = function(){};
    window.aclib.runBanner = function(){};
    window.aclib.isSandboxed = function(){ return false; };
    window.aclib.isAdbMode = function(){ return false; };

    // NOTE: window.open() sengaja TIDAK di-stub di sini. Native WebViewChromiumClient
    // dengan setSupportMultipleWindows(true) + onCreateWindow() yang menyediakan child
    // WebView membuat window.open() return WindowProxy ASLI — persis yang script #F butuhkan.

    // 4. Sapu sisa overlay/teks penolakan setelah DOM ada.
    function sweep(){
      try {
        var ov = document.getElementById('sbxwo'); if (ov) ov.remove();
        var dn = document.getElementById('dontfoid'); if (dn) dn.remove();
        var b = document.body;
        if (b) {
          var t = b.innerText || '';
          if (t.indexOf('Remove sandbox') >= 0 || t.indexOf('Ad Functionality Blocked') >= 0) {
            // Hapus HANYA node teks penolakan, JANGAN innerHTML (itu menghanguskan
            // <script> player dan <video> yang baru saja dipasang bundle-jw).
            var walker = document.createTreeWalker(b, NodeFilter.SHOW_TEXT, null, false);
            var doomed = [], n;
            while ((n = walker.nextNode())) {
              var v = n.nodeValue || '';
              if (v.indexOf('Remove sandbox') >= 0 || v.indexOf('Ad Functionality Blocked') >= 0 ||
                  v.indexOf('sandbox attributes') >= 0) doomed.push(n);
            }
            doomed.forEach(function(x){ if (x.parentNode) x.parentNode.removeChild(x); });
          }
        }
        var v = document.querySelector('video');
        if (v) { v.muted=false; v.volume=1.0; if (v.paused) v.play().catch(function(){}); }
        if (window.jwplayer) {
          var p = jwplayer();
          if (p && p.get){ p.setMute(false); p.setVolume(100); if(!p.getState||p.getState()!=='playing') p.play(); }
        }
      } catch(e){}
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', sweep);
    sweep();
    var t = setInterval(sweep, 600);
    setTimeout(function(){ clearInterval(t); }, 60000);
  } catch(e){}
})();
"""

private val TRACKER_HOSTS = listOf(
    "usrpubtrk.com", "omg10.com", "al5sm.com", "quantserve.com",
    "outbrain.com", "scorecardresearch", "amazon-adsystem",
    "taboola", "criteo", "pubmatic", "rubiconproject", "adsrvr", "casalemedia"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    val web = remember {
        WebView(context).apply {
            WebView.setWebContentsDebuggingEnabled(true)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                // NOTE: setSupportMultipleWindows sengaja DIMATIKAN. Child WebView dari
                // onCreateWindow ternyata mengosongkan DOM main frame (document.open/write
                // dari popup ad). Cukup document-start defuser saja.
                // javaScriptCanOpenWindowsAutomatically = true
                // setSupportMultipleWindows(true)
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            setBackgroundColor(android.graphics.Color.BLACK)

            // Document-start injection — jalan SEBELUM script halaman, semua frame.
            try {
                WebViewCompat.addDocumentStartJavaScript(this, DEFUSER_JS, setOf("*"))
                android.util.Log.d("KaTvWV", "documentStart installed")
            } catch (e: Exception) {
                android.util.Log.e("KaTvWV", "documentStart failed: ${e.message}")
            }

            // WebChromeClient onCreateWindow DIMATIKAN sementara (isolasi variabel).
            // Child WebView mengosongkan DOM main frame lewat document.open/write.
            /*
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    val msg = resultMsg ?: return false
                    val transport = msg.obj as? WebView.WebViewTransport ?: return false
                    val ctx = view?.context ?: return false
                    val parentUa = view.settings.userAgentString
                    val child = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.userAgentString = parentUa
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                v: WebView?, request: WebResourceRequest?
                            ): Boolean {
                                return true
                            }
                        }
                    }
                    transport.webView = child
                    msg.sendToTarget()
                    return true
                }
            }
            */

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, u, favicon)
                    view?.evaluateJavascript(DEFUSER_JS, null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val req = request ?: return null
                    val uri = req.url
                    val host = uri.host?.lowercase() ?: return null
                    if (host == "dev.katv.local") return null // wrapper HTML lokal, jangan di-fetch
                    if (TRACKER_HOSTS.any { host.contains(it) }) {
                        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), null)
                    }
                    val accept = req.requestHeaders["Accept"] ?: ""
                    val isHtml = req.isForMainFrame || accept.contains("text/html")
                    if (req.method == "GET" && isHtml && (uri.scheme == "https" || uri.scheme == "http")) {
                        try {
                            val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 10000
                            conn.readTimeout = 15000
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", uaDesktop)
                            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                            conn.setRequestProperty("Accept-Encoding", "identity")
                            val code = conn.responseCode
                            val src = if (code in 200..299) conn.inputStream else conn.errorStream
                            var html = src.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            src.close()
                            if (host.contains("esportex.site")) {
                                // Hapus frame-buster: if(window.self===window.top){location.replace("google")}
                                html = html.replace(
                                    Regex("""if\s*\(\s*window\.self\s*===\s*window\.top\s*\)\s*\{\s*window\.location\.replace\([^)]*\)\s*;\s*\}"""),
                                    ""
                                )
                                android.util.Log.d("KaTvWV", "esportex frame-buster stripped")
                            }
                            val m = Regex("(?i)(<head[^>]*>)").find(html)
                            html = if (m != null)
                                html.substring(0, m.range.last + 1) + "<script>$DEFUSER_JS</script>" + html.substring(m.range.last + 1)
                            else "<script>$DEFUSER_JS</script>" + html
                            android.util.Log.d("KaTvWV", "rewrite OK len=${html.length} host=$host")
                            return WebResourceResponse(
                                "text/html", "UTF-8", code, conn.responseMessage ?: "OK",
                                mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("KaTvWV", "rewrite FAIL $host: ${e.message}")
                            return null
                        }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    super.onPageFinished(view, u)
                    view?.evaluateJavascript(DEFUSER_JS, null)
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(Unit) {
        onDispose { web.stopLoading(); web.destroy() }
    }
    Box(modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView({ web }, modifier = Modifier.fillMaxSize())
    }
}
