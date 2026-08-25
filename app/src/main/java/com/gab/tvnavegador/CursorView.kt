package com.gab.tvnavegador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Puntero virtual que se dibuja encima del WebView. El D-pad del mando lo mueve
 * y OK hace clic en la posicion actual, de forma que se puede usar cualquier web
 * pensada para raton desde el sofa.
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val arrow = Path()

    /** Oculta el puntero solo cuando el usuario lleva un rato sin moverlo. */
    private val hideRunnable = Runnable {
        animate().alpha(0f).setDuration(250).start()
    }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = w / 2f
            cursorY = h / 2f
        } else {
            cursorX = cursorX.coerceIn(0f, w.toFloat())
            cursorY = cursorY.coerceIn(0f, h.toFloat())
        }
    }

    /** Coloca el puntero en coordenadas absolutas, recortando a los bordes. */
    fun moveTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cursorY = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        wake()
        invalidate()
    }

    fun centre() = moveTo(width / 2f, height / 2f)

    /** Vuelve a mostrar el puntero y reprograma el auto-ocultado. */
    fun wake() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        alpha = 1f
        postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    fun hideNow() {
        removeCallbacks(hideRunnable)
        animate().cancel()
        alpha = 0f
    }

    /** Animacion breve de "pulsacion" para dar feedback al hacer clic. */
    fun flashClick() {
        wake()
        animate().scaleX(0.7f).scaleY(0.7f).setDuration(70).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alpha <= 0.01f) return

        val x = cursorX
        val y = cursorY

        // Sombra suave para que el puntero se vea sobre fondos claros y oscuros.
        canvas.drawCircle(x + 2f, y + 3f, 13f, halo)

        arrow.reset()
        arrow.moveTo(x, y)
        arrow.lineTo(x, y + 26f)
        arrow.lineTo(x + 6.5f, y + 20f)
        arrow.lineTo(x + 11f, y + 29f)
        arrow.lineTo(x + 16f, y + 26.5f)
        arrow.lineTo(x + 11.5f, y + 18f)
        arrow.lineTo(x + 19f, y + 17f)
        arrow.close()

        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
    }

    companion object {
        private const val AUTO_HIDE_MS = 4000L
    }
}
