package com.gab.tvnavegador

/**
 * Fragmentos de JavaScript que se inyectan en la pagina para poder gobernar el
 * reproductor de video con las teclas del mando. Se elige siempre el <video>
 * de mayor superficie visible, que en la practica es el reproductor principal.
 */
object JsSnippets {

    private const val PICK_VIDEO = """
        var __vs = Array.prototype.slice.call(document.querySelectorAll('video'));
        __vs = __vs.filter(function (v) { return v.offsetWidth > 0 && v.offsetHeight > 0; });
        __vs.sort(function (a, b) {
            return (b.offsetWidth * b.offsetHeight) - (a.offsetWidth * a.offsetHeight);
        });
        var __v = __vs[0];
    """

    val TOGGLE_PLAY = """
        (function () { $PICK_VIDEO
            if (!__v) { return 'no-video'; }
            if (__v.paused) { __v.play(); return 'play'; }
            __v.pause(); return 'pause';
        })();
    """

    val PLAY = """
        (function () { $PICK_VIDEO
            if (__v) { __v.play(); return 'play'; } return 'no-video';
        })();
    """

    val PAUSE = """
        (function () { $PICK_VIDEO
            if (__v) { __v.pause(); return 'pause'; } return 'no-video';
        })();
    """

    /** Avanza o retrocede la reproduccion los segundos indicados. */
    fun seek(seconds: Int) = """
        (function () { $PICK_VIDEO
            if (!__v) { return 'no-video'; }
            __v.currentTime = Math.max(0, __v.currentTime + ($seconds));
            return String(Math.round(__v.currentTime));
        })();
    """

    /** Pide pantalla completa al video principal. */
    val REQUEST_FULLSCREEN = """
        (function () { $PICK_VIDEO
            if (!__v) { return 'no-video'; }
            var fn = __v.requestFullscreen || __v.webkitRequestFullscreen
                  || __v.webkitEnterFullscreen || __v.mozRequestFullScreen;
            if (fn) { try { fn.call(__v); return 'ok'; } catch (e) { return 'error'; } }
            return 'unsupported';
        })();
    """

    /** true si hay algun video reproduciendose ahora mismo. */
    val IS_PLAYING = """
        (function () { $PICK_VIDEO
            return (__v && !__v.paused) ? 'true' : 'false';
        })();
    """
}
