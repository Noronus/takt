package app.takt.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.takt.TaktApp

/** User-adjustable animation speed (Settings). All app animations and map camera moves go through this. */
object Anim {
    var scale by mutableFloatStateOf(TaktApp.instance.prefs.animScale)
        private set

    fun set(v: Float) { scale = v; TaktApp.instance.prefs.animScale = v }

    fun ms(base: Int): Int = (base * scale).toInt()
    fun <T> spec(base: Int): FiniteAnimationSpec<T> = if (scale <= 0f) snap() else tween(ms(base))
    fun offset(base: Int): FiniteAnimationSpec<IntOffset> = spec(base)
    fun size(base: Int): FiniteAnimationSpec<IntSize> = spec(base)

    val options = listOf(0f to "Aus", 0.5f to "Schnell", 1f to "Normal", 1.6f to "Langsam")
}
