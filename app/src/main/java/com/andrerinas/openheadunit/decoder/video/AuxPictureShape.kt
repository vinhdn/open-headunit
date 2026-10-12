package com.andrerinas.openheadunit.decoder.video

/**
 * What part of the second screen Android Auto draws: all of it, a centred rectangle of one shape,
 * or a fixed resolution scaled to fit and centred. The rest of the panel stays black.
 *
 * A 1920x720 taplo with [Aspect] 3:2 is told it is 1080x720, and the picture lands in the middle,
 * 420 px from each side.
 */
sealed class AuxPictureShape {
    abstract val stored: String

    /** The whole panel. */
    object Fill : AuxPictureShape() {
        override val stored = FILL
    }

    /** The largest rectangle of [width]:[height] that fits the panel, centred. */
    data class Aspect(val width: Int, val height: Int) : AuxPictureShape() {
        override val stored get() = "$width:$height"
    }

    /** Exactly [width]x[height] for the phone, scaled to fit the panel and centred. */
    data class Size(val width: Int, val height: Int) : AuxPictureShape() {
        override val stored get() = "${width}x$height"
    }

    /** The size the phone is told the display is, for a panel of [panelW]x[panelH]. */
    fun announcedSize(panelW: Int, panelH: Int): Pair<Int, Int> = when (this) {
        Fill -> panelW to panelH
        is Aspect -> fit(panelW, panelH, width, height).let { it.width to it.height }
        is Size -> width to height
    }

    /** Where the picture sits on a [panelW]x[panelH] panel. */
    fun placement(panelW: Int, panelH: Int): Rect = when (this) {
        Fill -> Rect(0, 0, panelW.coerceAtLeast(0), panelH.coerceAtLeast(0))
        is Aspect -> fit(panelW, panelH, width, height)
        is Size -> fit(panelW, panelH, width, height)
    }

    /** Width over height of the picture, or 0 for the whole panel, which is what a client lays out by. */
    fun aspect(): Float = when (this) {
        Fill -> 0f
        is Aspect -> width.toFloat() / height
        is Size -> width.toFloat() / height
    }

    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    companion object {
        const val FILL = "fill"

        /**
         * What the settings offer, in order. Lazy: [Fill] is a subclass, so a plain initialiser runs
         * while this class is still initialising and would read [Fill] as null.
         */
        val CHOICES: List<AuxPictureShape> by lazy {
            listOf(
                Fill, Aspect(16, 9), Aspect(3, 2), Aspect(4, 3), Aspect(1, 1),
                Size(800, 480), Size(1280, 720), Size(1920, 1080),
            )
        }

        fun of(stored: String?): AuxPictureShape = CHOICES.firstOrNull { it.stored == stored } ?: Fill

        /**
         * The largest [ratioW]:[ratioH] rectangle inside the panel, centred, with even sides: video
         * dimensions are, and an odd one would be rounded by the phone anyway.
         */
        fun fit(panelW: Int, panelH: Int, ratioW: Int, ratioH: Int): Rect {
            val pw = panelW.coerceAtLeast(2)
            val ph = panelH.coerceAtLeast(2)
            val byHeight = pw.toLong() * ratioH >= ph.toLong() * ratioW
            val w = if (byHeight) (ph.toLong() * ratioW / ratioH).toInt() else pw
            val h = if (byHeight) ph else (pw.toLong() * ratioH / ratioW).toInt()
            val even = { v: Int -> (v / 2 * 2).coerceAtLeast(2) }
            val ew = even(w)
            val eh = even(h)
            return Rect((pw - ew) / 2, (ph - eh) / 2, ew, eh)
        }
    }
}
