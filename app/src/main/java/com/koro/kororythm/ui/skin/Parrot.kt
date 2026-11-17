package com.koro.kororythm.ui.skin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The Kororythm parrot (same drawing as the launcher icon), drawn in a 108x108 box so the
 * wings can move on their own for the "Wings Up" animation.
 */
object Parrot {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    val wingLeft = p("M48,52 C41,43 31,34 21,29 C19,29 18.5,31.5 20.5,32.5 L26,37 C21.5,36.5 18.5,38.5 19.5,40.5 C20.5,42 23,42 28,43 C23.5,44.5 21.5,46.5 22.5,48.5 C24,50 27,49 32,48 C28.5,50.5 27.5,52.5 29,54.5 C31,56 35,54 38,52 C37,55.5 38.5,58 41.5,58 C44,57.5 46,60 47,62 Z")
    val wingRight = p("M60,52 C67,43 77,34 87,29 C89,29 89.5,31.5 87.5,32.5 L82,37 C86.5,36.5 89.5,38.5 88.5,40.5 C87.5,42 85,42 80,43 C84.5,44.5 86.5,46.5 85.5,48.5 C84,50 81,49 76,48 C79.5,50.5 80.5,52.5 79,54.5 C77,56 73,54 70,52 C71,55.5 69.5,58 66.5,58 C64,57.5 62,60 61,62 Z")
    val featherLeft = p("M45,55 L24,33.5 M45,57 L23,41 M45.5,59 L26,47.5 M46,60.5 L32,53")
    val featherRight = p("M63,55 L84,33.5 M63,57 L85,41 M62.5,59 L82,47.5 M62,60.5 L76,53")
    val tail = p("M51.5,75 C50.5,80 50,85 51.5,89 C52.5,86 53,82 54,77 C55,82 55.5,86 56.5,89 C58,85 57.5,80 56.5,75 Z")
    val tailLine = p("M54,77 L54,88")
    val body = p("M48,50 Q43.5,62 50,70 L54,77 L58,70 Q64.5,62 60,50 Q54,47.5 48,50 Z")
    val crest = p("M52,31.5 Q51,26 54,23 Q54.5,27 56,31")
    val head = p("M45,40 A9,9 0 1,1 63,40 A9,9 0 1,1 45,40 Z")
    val eyeRings = p("M48.3,38.4 A1.9,1.9 0 1,1 52.1,38.4 A1.9,1.9 0 1,1 48.3,38.4 Z M55.9,38.4 A1.9,1.9 0 1,1 59.7,38.4 A1.9,1.9 0 1,1 55.9,38.4 Z")
    val pupils = p("M49.3,38.4 A0.9,0.9 0 1,1 51.1,38.4 A0.9,0.9 0 1,1 49.3,38.4 Z M56.9,38.4 A0.9,0.9 0 1,1 58.7,38.4 A0.9,0.9 0 1,1 56.9,38.4 Z")
    val beak = p("M50,43.2 C50,41 58,41 58,43.2 C58,47 56.5,50.5 54,52.5 C51.5,50.5 50,47 50,43.2 Z")
    val beakLine = p("M51.4,46.4 Q54,48.2 56.6,46.4")
    val band = p("M42.5,41 Q42.5,27.5 54,27.5 Q65.5,27.5 65.5,41")
    val cups = p("M40,37 h5 v10 h-5 a2,2 0 0,1 -2,-2 v-6 a2,2 0 0,1 2,-2 Z M63,37 h5 a2,2 0 0,1 2,2 v6 a2,2 0 0,1 -2,2 h-5 Z")

    val shoulderLeft = Offset(48f, 54f)
    val shoulderRight = Offset(60f, 54f)
    val cupLeft = Offset(41f, 42f)
    val cupRight = Offset(67f, 42f)
    const val FOLD_DEGREES = 95f
}

/**
 * Draws the parrot with its top-left at [topLeft] and side length [side] px.
 * [wingSpread] 0 = folded against the body, 1 = fully spread. [phonesGlow] 0..1 adds a halo
 * around the headphones.
 */
fun DrawScope.drawParrot(
    topLeft: Offset,
    side: Float,
    wingSpread: Float = 1f,
    body: Color = Neon.Cyan,
    phones: Color = Neon.Orange,
    phonesGlow: Float = 0f,
) {
    val k = side / 108f
    translate(topLeft.x, topLeft.y) {
        scale(k, k, pivot = Offset.Zero) {
            val line = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val halo = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun outlined(path: Path, fillAlpha: Float, color: Color = body) {
                if (fillAlpha > 0f) drawPath(path, color.copy(alpha = fillAlpha))
                drawPath(path, color.copy(alpha = 0.25f), style = halo)
                drawPath(path, color, style = line)
            }
            val fold = (1f - wingSpread) * Parrot.FOLD_DEGREES
            rotate(-fold, Parrot.shoulderLeft) {
                outlined(Parrot.wingLeft, 0.2f)
                drawPath(Parrot.featherLeft, body.copy(alpha = 0.6f), style = Stroke(1f, cap = StrokeCap.Round))
            }
            rotate(fold, Parrot.shoulderRight) {
                outlined(Parrot.wingRight, 0.2f)
                drawPath(Parrot.featherRight, body.copy(alpha = 0.6f), style = Stroke(1f, cap = StrokeCap.Round))
            }
            outlined(Parrot.tail, 0.2f)
            drawPath(Parrot.tailLine, body.copy(alpha = 0.6f), style = Stroke(1f, cap = StrokeCap.Round))
            outlined(Parrot.body, 0.27f)
            outlined(Parrot.crest, 0f)
            drawPath(Parrot.head, Color(0xFF061019))
            outlined(Parrot.head, 0f)
            drawPath(Parrot.eyeRings, Neon.Ink.copy(alpha = body.alpha))
            drawPath(Parrot.pupils, Color(0xFF061019))
            drawPath(Parrot.beak, Neon.Beak.copy(alpha = body.alpha))
            drawPath(Parrot.beakLine, Color(0xFF8A5A00).copy(alpha = body.alpha), style = Stroke(0.9f, cap = StrokeCap.Round))
            if (phonesGlow > 0f) {
                drawCircle(phones.copy(alpha = 0.35f * phonesGlow), 7f, Parrot.cupLeft)
                drawCircle(phones.copy(alpha = 0.35f * phonesGlow), 7f, Parrot.cupRight)
                drawPath(Parrot.band, phones.copy(alpha = 0.4f * phonesGlow), style = Stroke(6f, cap = StrokeCap.Round))
            }
            drawPath(Parrot.band, phones, style = Stroke(2.2f, cap = StrokeCap.Round))
            drawPath(Parrot.cups, phones)
        }
    }
}
