package com.gab.tvnavegador

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * Navegador web pensado para manejarse con el mando de un Android TV.
 *
 * Es un navegador de proposito general: carga la web que se le indique y no
 * conoce ni interpreta el contenido de ningun sitio concreto. Toda la comodidad
 * viene de la capa de entrada (puntero virtual con el D-pad, teclas multimedia,
 * pantalla completa) y de una configuracion de WebView permisiva para que las
 * webs modernas se rendericen igual que en un movil o un portatil.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var prefs: Prefs

    private var chromeClient: WebChromeClient? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var longBackHandled = false
    /** false cuando el reproductor esta en un iframe de otro dominio. */
    private var hasNativeVideo = false
    private val hideHintRunnable = Runnable { hint.visibility = View.GONE }

    // ---------------------------------------------------------------- ciclo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webview)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        cursor = findViewById(R.id.cursor)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)

        // Mientras se ve un video la pantalla no debe apagarse.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configureWebView()
        applyNavMode()

        val start = intent?.dataString?.takeIf { it.isNotBlank() }
            ?: prefs.lastUrl.takeIf { prefs.restoreLast && it.isNotBlank() }
            ?: prefs.homePage
        webView.loadUrl(start)

        showHint("Mantén ATRÁS para abrir el menú")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.isNotBlank() }?.let { webView.loadUrl(it) }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.url?.let { if (it.startsWith("http")) prefs.lastUrl = it }
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings

        // Motor: todo lo que una web moderna espera encontrar.
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setGeolocationEnabled(false)

        // Los reproductores deben poder arrancar sin un toque previo en pantalla,
        // porque en una TV no hay gesto tactil que dar.
        s.mediaPlaybackRequiresUserGesture = false

        // Muchas webs sirven la pagina por https y algun recurso por http.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        applyUserAgent()
        webView.setInitialScale(prefs.zoomPercent)

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setBackgroundColor(android.graphics.Color.BLACK)

        // Sesiones iniciadas por el usuario: cookies propias y de terceros.
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleUrl(request.url?.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                handleUrl(url)

            // Corre en hilo secundario: AdBlocker es inmutable y atomico.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!prefs.blockAds) return null
                val target = request.url?.toString() ?: return null
                if (!AdBlocker.shouldBlock(target)) return null
                return WebResourceResponse(
                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                url?.let { if (it.startsWith("http")) prefs.lastUrl = it }
                installSpatialNav()
                installAdBlock()
                refreshVideoReach()
            }

            // Las webs de una sola pagina cambian de vista sin recargar.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                installSpatialNav()
                installAdBlock()
            }
        }

        chromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // Pantalla completa del reproductor de video.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                refreshVideoReach()
                cursor.hideNow()
                js(SpatialNav.CLEAR)
                enterImmersive(true)
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                fullscreenContainer.removeView(v)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                enterImmersive(false)
                applyNavMode()
            }

            // Enlaces con target=_blank y window.open: se abren en esta misma vista
            // en lugar de perderse, que es lo esperable en una TV.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // Con el bloqueador activo la ventana no llega a abrirse y la
                // pagina actual se queda donde esta, que es justo lo que
                // rompe los popunder.
                if (prefs.blockAds) {
                    showHint("Ventana emergente bloqueada")
                    return false
                }
                val proxy = WebView(this@MainActivity)
                proxy.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        request.url?.let { webView.loadUrl(it.toString()) }
                        proxy.post { proxy.destroy() }
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean {
                        url?.let { webView.loadUrl(it) }
                        proxy.post { proxy.destroy() }
                        return true
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport
                transport?.webView = proxy
                resultMsg.sendToTarget()
                return true
            }

            // Widevine / EME: sin esto el video protegido no arranca.
            override fun onPermissionRequest(request: PermissionRequest) {
                val protectedMedia = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                val granted = request.resources.filter { it == protectedMedia }
                if (granted.isNotEmpty()) request.grant(granted.toTypedArray())
                else request.deny()
            }
        }
        webView.webChromeClient = chromeClient

        // Descargas: se delegan al gestor del sistema.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                showHint("Descarga iniciada")
            } catch (e: Exception) {
                showHint("No se pudo descargar")
            }
        }
    }

    private fun applyUserAgent() {
        webView.settings.userAgentString = if (prefs.desktopUa) DESKTOP_UA else null
    }

    /**
     * Deja pasar http/https al WebView y manda al sistema cualquier otro esquema
     * (intent://, market://, mailto:...) para no quedarse en blanco.
     */
    private fun handleUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // Redireccion a un dominio de anuncios: se corta la navegacion.
            if (prefs.blockAds && AdBlocker.shouldBlock(url)) {
                showHint("Redirección publicitaria bloqueada")
                return true
            }
            return false
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            true // Sin app capaz de abrirlo: se ignora en vez de romper la navegacion.
        }
    }

    private fun enterImmersive(on: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // -------------------------------------------------------------- cursor

    private fun applyNavMode() {
        if (prefs.navMode == Prefs.NAV_CURSOR && customView == null) {
            cursor.visibility = View.VISIBLE
            cursor.wake()
        } else {
            cursor.visibility = View.GONE
        }
        if (prefs.navMode == Prefs.NAV_SPATIAL) installSpatialNav() else js(SpatialNav.CLEAR)
        webView.requestFocus()
    }

    /** Inyecta el motor de navegacion espacial en la pagina actual. */
    private fun installSpatialNav() {
        if (prefs.navMode == Prefs.NAV_SPATIAL) js(SpatialNav.INSTALL)
    }

    /** Inyecta el filtro cosmetico y la anulacion de window.open. */
    private fun installAdBlock() {
        if (prefs.blockAds) js(AdBlocker.COSMETIC_JS)
    }

    /**
     * Averigua si el video es alcanzable desde este contexto. Si no lo es
     * —reproductor incrustado desde otro dominio— las teclas del mando se
     * dejan pasar para que las gobierne el propio reproductor.
     */
    private fun refreshVideoReach() {
        webView.evaluateJavascript(JsSnippets.HAS_NATIVE_VIDEO) { raw ->
            hasNativeVideo = raw?.contains("true") == true
        }
    }

    /** Paso del puntero: cuanto mas se mantiene pulsado el D-pad, mas rapido va. */
    private fun stepFor(repeat: Int): Float = when {
        repeat == 0 -> 16f
        repeat < 6 -> 24f
        repeat < 14 -> 44f
        repeat < 26 -> 70f
        else -> 100f
    }

    private fun moveCursor(dx: Float, dy: Float) {
        var nx = cursor.cursorX + dx
        var ny = cursor.cursorY + dy

        // Al llegar a los bordes vertical y horizontal se desplaza la pagina,
        // que es lo que permite recorrer sitios largos sin barra de scroll.
        val margin = EDGE_MARGIN
        if (ny > root.height - margin) {
            webView.scrollBy(0, EDGE_SCROLL)
            ny = (root.height - margin).toFloat()
        } else if (ny < margin) {
            webView.scrollBy(0, -EDGE_SCROLL)
            ny = margin.toFloat()
        }
        if (nx > root.width - margin) {
            webView.scrollBy(EDGE_SCROLL, 0)
            nx = (root.width - margin).toFloat()
        } else if (nx < margin) {
            webView.scrollBy(-EDGE_SCROLL, 0)
            nx = margin.toFloat()
        }

        cursor.moveTo(nx, ny)
    }

    /** Sintetiza un toque en la posicion del puntero para que la web lo reciba. */
    private fun clickAtCursor() {
        val x = cursor.cursorX
        val y = cursor.cursorY
        val now = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        webView.dispatchTouchEvent(down)
        down.recycle()

        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(up)
        up.recycle()

        cursor.flashClick()
    }

    // --------------------------------------------------------------- teclas

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ATRAS se gestiona en onKeyDown/onKeyLongPress/onKeyUp para poder
        // distinguir pulsacion corta (volver) de larga (menu).
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN && handleKeyDown(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKeyDown(event: KeyEvent): Boolean {
        val playing = customView != null

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                js(JsSnippets.TOGGLE_PLAY); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                js(JsSnippets.seek(SEEK_SECONDS)); showHint("+$SEEK_SECONDS s"); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                js(JsSnippets.seek(-SEEK_SECONDS)); showHint("-$SEEK_SECONDS s"); return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                showMenu(); return true
            }
            // Atajo directo: las teclas que suelen sobrar en los mandos de TV.
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_BUTTON_X -> {
                cycleNavMode(); return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                showUrlDialog(); return true
            }
        }

        // Con el video a pantalla completa el mando gobierna la reproduccion,
        // pero solo si el video es alcanzable: si esta incrustado desde otro
        // dominio, consumir las teclas dejaria el reproductor sin control.
        if (playing) {
            if (!hasNativeVideo) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    js(JsSnippets.TOGGLE_PLAY); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    js(JsSnippets.seek(SEEK_SECONDS)); showHint("+$SEEK_SECONDS s"); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    js(JsSnippets.seek(-SEEK_SECONDS)); showHint("-$SEEK_SECONDS s"); return true
                }
            }
            return false
        }

        return when (prefs.navMode) {
            Prefs.NAV_SPATIAL -> spatialKey(event)
            Prefs.NAV_CURSOR -> cursorKey(event)
            else -> false
        }
    }

    /** El D-pad recorre los elementos accionables de la web. */
    private fun spatialKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> js(SpatialNav.move("up"))
            KeyEvent.KEYCODE_DPAD_DOWN -> js(SpatialNav.move("down"))
            KeyEvent.KEYCODE_DPAD_LEFT -> js(SpatialNav.move("left"))
            KeyEvent.KEYCODE_DPAD_RIGHT -> js(SpatialNav.move("right"))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                js(SpatialNav.ACTIVATE)
            else -> return false
        }
        return true
    }

    /** El D-pad mueve un puntero de raton sobre la pagina. */
    private fun cursorKey(event: KeyEvent): Boolean {
        val step = stepFor(event.repeatCount)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveCursor(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(step, 0f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> clickAtCursor()
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            longBackHandled = true
            showMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (longBackHandled) {
                longBackHandled = false
                return true
            }
            goBackOrExit()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun goBackOrExit() {
        when {
            customView != null -> chromeClient?.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Salir")
            .setMessage("¿Cerrar el navegador?")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Seguir viendo", null)
            .show()
    }

    // ----------------------------------------------------------------- menu

    private fun showMenu() {
        val navLabel = "Modo del mando: " + when (prefs.navMode) {
            Prefs.NAV_SPATIAL -> "elementos"
            Prefs.NAV_CURSOR -> "puntero"
            else -> "desplazamiento"
        }
        val blockLabel = if (prefs.blockAds) {
            "Bloqueador: activado (" + AdBlocker.blockedCount + " bloqueados)"
        } else {
            "Bloqueador: desactivado"
        }
        val uaLabel = if (prefs.desktopUa) "Vista: escritorio" else "Vista: móvil"

        val items = arrayOf(
            navLabel,
            blockLabel,
            "Ir a una dirección…",
            "Marcadores",
            "Añadir esta página a marcadores",
            "Ir a la página de inicio",
            "Fijar esta página como inicio",
            "Recargar",
            "Atrás",
            "Adelante",
            uaLabel,
            "Zoom: ${prefs.zoomPercent}%",
            "Reproducir / Pausar vídeo",
            "Vídeo a pantalla completa",
            "Borrar cookies y datos",
            "Diagnóstico de navegación",
            "Ayuda del mando"
        )

        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Menú")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNavModeDialog()
                    1 -> {
                        prefs.blockAds = !prefs.blockAds
                        webView.reload()
                        showHint(
                            if (prefs.blockAds) "Bloqueador activado"
                            else "Bloqueador desactivado"
                        )
                    }
                    2 -> showUrlDialog()
                    3 -> showBookmarks()
                    4 -> {
                        val u = webView.url
                        if (!u.isNullOrBlank()) {
                            prefs.addBookmark(webView.title ?: u, u)
                            showHint("Marcador añadido")
                        }
                    }
                    5 -> webView.loadUrl(prefs.homePage)
                    6 -> {
                        webView.url?.let {
                            prefs.homePage = it
                            showHint("Página de inicio actualizada")
                        }
                    }
                    7 -> webView.reload()
                    8 -> if (webView.canGoBack()) webView.goBack()
                    9 -> if (webView.canGoForward()) webView.goForward()
                    10 -> {
                        prefs.desktopUa = !prefs.desktopUa
                        applyUserAgent()
                        webView.reload()
                        showHint(if (prefs.desktopUa) "Vista de escritorio" else "Vista móvil")
                    }
                    11 -> showZoomDialog()
                    12 -> js(JsSnippets.TOGGLE_PLAY)
                    13 -> js(JsSnippets.REQUEST_FULLSCREEN)
                    14 -> clearBrowsingData()
                    15 -> showDiagnostics()
                    16 -> showHelp()
                }
            }
            .show()
    }

    /**
     * Informe de lo que el motor de navegacion ve en la pagina actual. Sirve
     * para afinar la heuristica contra una web concreta sin tener que abrirla.
     */
    private fun showDiagnostics() {
        webView.evaluateJavascript(SpatialNav.DIAGNOSE) { raw ->
            val report = buildString {
                append("Página: ")
                append(webView.url ?: "—")
                append("\n\n")
                try {
                    // evaluateJavascript devuelve el valor codificado como JSON,
                    // asi que la cadena viene envuelta una vez de mas.
                    val unwrapped = JSONTokener(raw).nextValue()
                    val obj = JSONObject(
                        if (unwrapped is String) unwrapped else raw
                    )
                    append("Elementos navegables: ")
                    append(obj.optInt("total", 0))
                    append("\n  por selector: ").append(obj.optInt("base", 0))
                    append("\n  por cursor:pointer: ").append(obj.optInt("extras", 0))
                    append("\n  superpuestos descartados: ")
                    append(obj.optInt("suppressed", 0))
                    append("\n\nReparto por etiqueta:\n")
                    val tags = obj.optJSONObject("tags")
                    if (tags != null) {
                        val keys = tags.keys().asSequence().toList()
                            .sortedByDescending { tags.optInt(it) }
                        keys.take(10).forEach {
                            append("  ").append(it).append(" = ")
                                .append(tags.optInt(it)).append("\n")
                        }
                    }
                    val f = obj.optJSONObject("focused")
                    append("\nElemento con el foco:\n")
                    if (f == null) {
                        append("  ninguno todavía\n")
                    } else {
                        append("  <").append(f.optString("tag")).append(">")
                        append("  ").append(f.optInt("w")).append("×")
                            .append(f.optInt("h")).append(" px\n")
                        val id = f.optString("id")
                        if (id.isNotBlank()) append("  id: ").append(id).append("\n")
                        val cls = f.optString("cls")
                        if (cls.isNotBlank()) append("  class: ").append(cls).append("\n")
                    }
                } catch (e: Exception) {
                    append("No se pudo leer el diagnóstico.\n")
                    append("El motor puede no estar inyectado en esta página.\n")
                }
                append("\nPeticiones bloqueadas: ")
                append(AdBlocker.blockedCount)
            }

            AlertDialog.Builder(this, R.style.DialogDark)
                .setTitle("Diagnóstico de navegación")
                .setMessage(report)
                .setPositiveButton("Cerrar", null)
                .show()
        }
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(webView.url ?: prefs.homePage)
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }

        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Dirección o búsqueda")
            .setView(box)
            .setPositiveButton("Ir") { _, _ ->
                webView.loadUrl(normalise(input.text.toString().trim()))
            }
            .setNegativeButton("Cancelar", null)
            .show()

        input.requestFocus()
    }

    /** Acepta una URL, un dominio suelto o texto libre (que va al buscador). */
    private fun normalise(raw: String): String {
        if (raw.isBlank()) return prefs.homePage
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val looksLikeDomain = raw.contains('.') && !raw.contains(' ')
        if (looksLikeDomain) return "https://$raw"
        return "https://duckduckgo.com/?q=" + Uri.encode(raw)
    }

    private fun showBookmarks() {
        val list = prefs.bookmarks()
        if (list.isEmpty()) {
            showHint("Todavía no hay marcadores")
            return
        }
        val labels = list.map { it.title }.toTypedArray()

        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Marcadores")
            .setItems(labels) { _, which -> webView.loadUrl(list[which].url) }
            .setNeutralButton("Eliminar…") { _, _ -> showBookmarkRemoval(list) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showBookmarkRemoval(list: List<Bookmark>) {
        val labels = list.map { it.title }.toTypedArray()
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Eliminar marcador")
            .setItems(labels) { _, which ->
                prefs.removeBookmark(list[which].url)
                showHint("Marcador eliminado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Rota entre elementos, puntero y desplazamiento sin abrir el menu. */
    private fun cycleNavMode() {
        prefs.navMode = (prefs.navMode + 1) % 3
        applyNavMode()
        showHint(
            when (prefs.navMode) {
                Prefs.NAV_SPATIAL -> "Modo: elementos — el D-pad salta por la web"
                Prefs.NAV_CURSOR -> "Modo: puntero — el D-pad mueve el cursor"
                else -> "Modo: desplazamiento — el D-pad mueve la página"
            }
        )
    }

    private fun showNavModeDialog() {
        val labels = arrayOf(
            "Elementos — salta por los enlaces y botones de la web",
            "Puntero — mueve un cursor de ratón por la pantalla",
            "Desplazamiento — solo mueve la página"
        )
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Modo del mando")
            .setSingleChoiceItems(labels, prefs.navMode) { dialog, which ->
                prefs.navMode = which
                applyNavMode()
                dialog.dismiss()
                showHint(
                    when (which) {
                        Prefs.NAV_SPATIAL -> "El D-pad recorre los elementos de la web"
                        Prefs.NAV_CURSOR -> "Puntero activado"
                        else -> "El D-pad desplaza la página"
                    }
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showZoomDialog() {
        val options = intArrayOf(50, 75, 90, 100, 110, 125, 150, 175, 200)
        val labels = options.map { "$it%" }.toTypedArray()
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Zoom de la página")
            .setItems(labels) { _, which ->
                prefs.zoomPercent = options[which]
                webView.setInitialScale(prefs.zoomPercent)
                webView.reload()
            }
            .show()
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Borrar datos")
            .setMessage("Se cerrarán las sesiones iniciadas en las webs.")
            .setPositiveButton("Borrar") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                showHint("Datos borrados")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHelp() {
        val text = """
            D-pad — salta entre los elementos de la web (el que tiene el
            foco se marca con un recuadro azul)
            OK — activa el elemento marcado
            ATRÁS — vuelve a la página anterior
            ATRÁS (mantener) — abre este menú
            PLAY/PAUSA — reproduce o pausa el vídeo
            AVANCE / RETROCESO — salta $SEEK_SECONDS segundos
            AMARILLO / INFO — cambia de modo del mando al vuelo

            Con el vídeo a pantalla completa, el D-pad izquierda y derecha
            salta en el tiempo y OK reproduce o pausa.

            Si una web concreta se recorre mal, cambia el «Modo del mando»
            desde este menú: «Puntero» mueve un cursor de ratón y
            «Desplazamiento» solo mueve la página.
        """.trimIndent()

        AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("Ayuda del mando")
            .setMessage(text)
            .setPositiveButton("Entendido", null)
            .show()
    }

    // ---------------------------------------------------------------- util

    private fun js(script: String) {
        webView.evaluateJavascript(script, null)
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHintRunnable)
        hint.postDelayed(hideHintRunnable, HINT_MS)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        private const val SEEK_SECONDS = 10
        private const val EDGE_MARGIN = 8
        private const val EDGE_SCROLL = 60
        private const val HINT_MS = 3200L
    }
}
