package com.andrerinas.openheadunit.taplo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.andrerinas.openheadunit.contract.TaploLink
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The turn-by-turn card drawn over the taplo picture, from the guidance Android Auto sends as text
 * (see [TaploLink.MSG_NAV]): the next manoeuvre, how far it is, the road it leads onto, and the
 * arrival time and what is left of the route when the phone sends those.
 */
class NavCardView(context: Context) : LinearLayout(context) {

    private val glyph = text(Color.WHITE)
    private val distance = text(Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
    private val road = text(Color.WHITE).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val summary = text(Color.parseColor("#B0B6BE"))

    /** The text sizes at 100%, in sp. */
    private val baseSizes = listOf(glyph to 34f, distance to 30f, road to 19f, summary to 16f)

    init {
        orientation = VERTICAL
        val pad = dp(14)
        setPadding(pad, dp(10), pad, dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E61B1E23"))
            cornerRadius = dp(14).toFloat()
        }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(glyph)
            addView(distance, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10)
            })
        })
        addView(road)
        addView(summary)
        setTextPercent(100)
        visibility = GONE
    }

    /** Scales every line of the card, [percent] of its normal size; chosen in the head unit's settings. */
    fun setTextPercent(percent: Int) {
        val scale = percent.coerceIn(50, 300) / 100f
        baseSizes.forEach { (view, sp) -> view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale) }
    }

    /** Keeps a long road name from pushing the card over the middle of the map. */
    fun maxWidthHint(px: Int) {
        if (px > 0) road.maxWidth = px
    }

    /** Shows [nav], or hides the card when no route is being guided. */
    fun update(nav: Bundle) {
        if (!nav.getBoolean(TaploLink.KEY_NAV_ACTIVE)) {
            visibility = GONE
            return
        }
        glyph.text = NavGlyphs.glyph(
            nav.getInt(TaploLink.KEY_NAV_EVENT, 0),
            nav.getInt(TaploLink.KEY_NAV_SIDE, 3),
            nav.getInt(TaploLink.KEY_NAV_ROUNDABOUT_EXIT, 0),
        )
        val meters = nav.getInt(TaploLink.KEY_NAV_DISTANCE_M, -1)
        distance.text = if (meters >= 0) formatDistance(meters) else nav.getString(TaploLink.KEY_NAV_ACTION).orEmpty()
        road.text = nav.getString(TaploLink.KEY_NAV_ROAD).orEmpty().takeIf { it != "—" }.orEmpty()
        road.visibility = if (road.text.isEmpty()) GONE else VISIBLE
        val parts = listOfNotNull(
            nav.getString(TaploLink.KEY_NAV_ETA)?.takeIf { it.isNotBlank() },
            nav.getLong(TaploLink.KEY_NAV_TOTAL_TIME_S, -1L).takeIf { it >= 0 }?.let { formatDuration(it) },
            nav.getInt(TaploLink.KEY_NAV_TOTAL_DISTANCE_M, -1).takeIf { it >= 0 }?.let { formatDistance(it) },
        )
        summary.text = parts.joinToString(" · ")
        summary.visibility = if (parts.isEmpty()) GONE else VISIBLE
        visibility = VISIBLE
    }

    private fun formatDistance(meters: Int): String = when {
        meters < 1000 -> "${(meters / 10.0).roundToInt() * 10} m"
        else -> NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(meters / 1000.0) + " km"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) {
            context.getString(R.string.nav_minutes, minutes.toInt())
        } else {
            context.getString(R.string.nav_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
    }

    private fun text(color: Int) = TextView(context).apply {
        setTextColor(color)
        includeFontPadding = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** An arrow for a manoeuvre, from the legacy NextTurnDetail values every guidance message is mapped to. */
object NavGlyphs {
    private const val LEFT = 1
    private const val RIGHT = 2

    fun glyph(event: Int, side: Int, roundaboutExit: Int): String = when (event) {
        3 -> if (side == LEFT) "↖" else "↗"                          // slight turn
        4, 5 -> if (side == LEFT) "↰" else "↱"                       // turn, sharp turn
        6 -> if (side == RIGHT) "↷" else "↶"                         // U-turn
        7, 8, 9, 10 -> if (side == LEFT) "↖" else "↗"                // ramps, fork, merge
        11, 12, 13 -> if (roundaboutExit > 0) "↻$roundaboutExit" else "↻"
        16, 17 -> "⛴"                                               // ferry
        19 -> "⚑"                                                    // destination
        else -> "↑"                                                  // depart, straight, name change
    }
}
