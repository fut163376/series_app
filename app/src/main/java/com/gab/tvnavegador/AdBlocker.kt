package com.gab.tvnavegador

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bloqueo de anuncios y rastreadores por dominio.
 *
 * Se consulta desde [android.webkit.WebViewClient.shouldInterceptRequest], que
 * corre en un hilo secundario, asi que la lista es inmutable y el contador
 * atomico. La coincidencia es por sufijo de dominio: bloquear "example.com"
 * bloquea tambien "cdn.example.com".
 */
object AdBlocker {

    private val counter = AtomicInteger(0)

    val blockedCount: Int get() = counter.get()

    fun resetCount() = counter.set(0)

    /** true si la peticion es de publicidad, rastreo o ventanas emergentes. */
    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false

        val host = hostOf(lower)
        if (host != null && hostBlocked(host)) {
            counter.incrementAndGet()
            return true
        }
        if (PATH_MARKERS.any { lower.contains(it) }) {
            counter.incrementAndGet()
            return true
        }
        return false
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }

    /** Comprueba el dominio y todos sus dominios padre. */
    private fun hostBlocked(host: String): Boolean {
        if (HOSTS.contains(host)) return true
        var dot = host.indexOf('.')
        while (dot >= 0 && dot < host.length - 1) {
            if (HOSTS.contains(host.substring(dot + 1))) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    /**
     * Fragmentos de ruta inequivocos. Se mantiene corta a proposito: un patron
     * demasiado general rompe webs legitimas.
     */
    private val PATH_MARKERS = listOf(
        "/adsbygoogle", "/pagead/", "/popunder", "/popads",
        "/adframe", "/ad-frame", "/adserver/", "/banner-ad"
    )

    /**
     * Redes de publicidad, rastreo y ventanas emergentes. No incluye CDNs ni
     * dominios de reproduccion de video, para no romper la visualizacion.
     */
    private val HOSTS: Set<String> = setOf(
        // Publicidad de Google
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adservice.google.com", "2mdn.net",
        // Analitica y rastreo
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com",
        "quantserve.com", "quantcount.com", "hotjar.com", "hotjar.io",
        "mixpanel.com", "segment.io", "segment.com", "chartbeat.com",
        "crazyegg.com", "mouseflow.com", "luckyorange.com", "clarity.ms",
        "amplitude.com", "fullstory.com", "inspectlet.com", "yandex.ru",
        "matomo.cloud", "statcounter.com", "histats.com",
        // Redes publicitarias generalistas
        "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "adform.net",
        "smartadserver.com", "casalemedia.com", "sharethrough.com",
        "media.net", "bidswitch.net", "33across.com", "onaudience.com",
        "yieldmo.com", "sonobi.com", "gumgum.com", "indexww.com",
        "contextweb.com", "adsrvr.org", "everesttech.net", "adroll.com",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "zedo.com", "teads.tv", "smaato.net", "inmobi.com", "applovin.com",
        "moatads.com", "servebom.com", "districtm.io", "lijit.com",
        // Ventanas emergentes y popunder, habituales en webs de video
        "popads.net", "popcash.net", "popunder.net", "propellerads.com",
        "propu.sh", "propellerclick.com", "adcash.com", "exoclick.com",
        "exosrv.com", "juicyads.com", "trafficjunky.com", "trafficfactory.biz",
        "hilltopads.net", "hilltopads.com", "adsterra.com", "adspyglass.com",
        "monetag.com", "clickadu.com", "onclickalgo.com", "onclicksuper.com",
        "onclickmax.com", "onclickperformance.com", "popmyads.com",
        "adnium.com", "adplxmd.com", "ad-maven.com", "admaven.com",
        "bidvertiser.com", "clicksor.com", "adcdnx.com", "adsmoregain.com",
        "vidoomy.com", "poweradnetwork.com", "coinzillatag.com",
        "adskeeper.com", "adskeeper.co.uk", "adnxs-simple.com",
        // Redes sociales usadas como rastreadores
        "connect.facebook.net", "facebook.net"
    )

    /**
     * Script que complementa el bloqueo de red: anula las aperturas de ventana
     * y oculta los huecos publicitarios que quedan vacios al bloquear la
     * peticion. Deliberadamente conservador: solo selectores inequivocos, para
     * no ocultar contenido legitimo.
     */
    val COSMETIC_JS = """
(function () {
  if (window.__tvblock) { window.__tvblock.sweep(); return 'ready'; }

  // Las ventanas emergentes se anulan devolviendo un objeto inerte, para que
  // el script que las abre no reviente y siga funcionando la pagina.
  function stubWindow() {
    var noop = function () {};
    return {
      closed: true, close: noop, focus: noop, blur: noop,
      document: { write: noop, writeln: noop, open: noop, close: noop },
      location: { href: '', replace: noop, assign: noop, reload: noop }
    };
  }
  try {
    window.open = function () { return stubWindow(); };
  } catch (e) { /* algunas paginas lo dejan de solo lectura */ }

  var SELECTORS = [
    'ins.adsbygoogle', '[data-ad-slot]', '[data-ad-client]',
    'iframe[src*="doubleclick"]', 'iframe[src*="googlesyndication"]',
    'iframe[src*="adservice"]', 'iframe[src*="popads"]',
    'iframe[src*="exoclick"]', 'iframe[src*="adsterra"]',
    'iframe[id^="google_ads"]', 'div[id^="google_ads"]',
    'div[id^="div-gpt-ad"]', '[aria-label="Advertisement"]',
    '[id^="adsense"]', '[class^="adsbygoogle"]'
  ].join(',');

  function sweep() {
    var n = 0;
    try {
      var nodes = document.querySelectorAll(SELECTORS);
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.getAttribute('data-tvblock') === '1') { continue; }
        el.setAttribute('data-tvblock', '1');
        el.style.setProperty('display', 'none', 'important');
        n++;
      }
    } catch (e) { /* selector no soportado */ }
    return n;
  }

  sweep();

  // Las webs cargan publicidad despues del render, asi que se repasa cuando
  // el arbol cambia, con freno para no penalizar el rendimiento.
  var pending = null;
  try {
    var mo = new MutationObserver(function () {
      if (pending) { return; }
      pending = setTimeout(function () { pending = null; sweep(); }, 600);
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e2) { /* sin MutationObserver */ }

  window.__tvblock = { sweep: sweep };
  return 'installed';
})();
"""
}
