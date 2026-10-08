package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy

private typealias Resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
private typealias FrameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType

/**
 * The video configuration announced for an auxiliary display, such as an instrument cluster.
 *
 * Deliberately not routed through HeadUnitScreenConfig: that object is the main canvas, and its
 * locked resolution has to keep meaning the main display.
 */
object AuxDisplayProfilePolicy {

    /** Shows the navigation map. Google's `KEYCODE_NAVIGATION`. */
    const val KEYCODE_NAVIGATION = 65538

    /** Shows the turn card. Google's `KEYCODE_TURN_CARD`, which is valid on auxiliary displays only. */
    const val KEYCODE_TURN_CARD = 65544

    /** The announced size of each resolution the protocol has, in the order it should be preferred. */
    private val SIZES: List<Pair<Resolution, Pair<Int, Int>>> = listOf(
        Resolution._800x480 to (800 to 480),
        Resolution._1280x720 to (1280 to 720),
        Resolution._1920x1080 to (1920 to 1080),
        Resolution._720x1280 to (720 to 1280),
        Resolution._1080x1920 to (1080 to 1920),
    )

    /** The pixel size of an announced resolution, which is the size the phone's stream decodes to. */
    fun dimensions(resolution: Resolution): Pair<Int, Int>? = SIZES.firstOrNull { it.first == resolution }?.second

    /**
     * How much to enlarge a frame, from its top-left, so the panel shows only the picture: Android
     * Auto draws the panel's size at the top-left of the frame and leaves the margins blank.
     */
    fun marginCropScale(profile: Profile): Pair<Float, Float> {
        val (w, h) = dimensions(profile.resolution) ?: return 1f to 1f
        val pictureW = (w - profile.widthMargin).coerceAtLeast(1)
        val pictureH = (h - profile.heightMargin).coerceAtLeast(1)
        return w.toFloat() / pictureW to h.toFloat() / pictureH
    }

    /** What goes into the auxiliary sink's `VideoConfiguration`. */
    data class Profile(
        val resolution: Resolution,
        val widthMargin: Int,
        val heightMargin: Int,
        val density: Int,
        val frameRate: FrameRate,
        /**
         * Width over height of one frame pixel as the panel shows it, x 10000; 10000 is square. Above
         * that the frame is squeezed vertically onto the panel, which the phone draws ahead of.
         */
        val pixelAspectRatioE4: Int = 10000,
    ) {
        /** The columns of the frame that end up on the panel. */
        val pictureWidthPx: Int
            get() = ((dimensions(resolution)?.first ?: 0) - widthMargin).coerceAtLeast(0)

        /** The rows of the frame that end up on the panel, which is what insets are measured in. */
        val pictureHeightPx: Int
            get() = ((dimensions(resolution)?.second ?: 0) - heightMargin).coerceAtLeast(0)
    }

    /**
     * The smallest standard resolution that contains the panel, with the remainder as margins.
     *
     * A margin is the part of the frame that will not be rendered, so it is the announced size minus
     * the panel rather than the other way round; a panel larger than anything on offer takes the
     * largest and no margin, and is scaled.
     */
    fun profileFor(widthPx: Int, heightPx: Int, densityDpi: Int, squeezeWide: Boolean = false): Profile {
        val panelW = widthPx.coerceAtLeast(1)
        val panelH = heightPx.coerceAtLeast(1)
        if (squeezeWide) squeezed(panelW, panelH, densityDpi)?.let { return it }
        val containing = SIZES
            .filter { it.second.first >= panelW && it.second.second >= panelH }
            .minByOrNull { it.second.first.toLong() * it.second.second }
        val chosen = containing ?: SIZES.maxByOrNull { it.second.first.toLong() * it.second.second }!!
        return Profile(
            resolution = chosen.first,
            widthMargin = (chosen.second.first - panelW).coerceAtLeast(0),
            heightMargin = (chosen.second.second - panelH).coerceAtLeast(0),
            // A density of 0 is what an unreadable panel reports, and the phone lays its UI out from
            // this, so it falls back to the baseline rather than going out as nothing.
            density = if (densityDpi in 1..640) densityDpi else 160,
            // 30 rather than 60 on purpose: a cluster view is a map or a turn card, and the second
            // stream's bandwidth is taken from the main picture's.
            frameRate = FrameRate._30,
        )
    }

    /**
     * A panel wider than 16:9 (a 1920x720 cluster) as a whole 16:9 frame of its width, squeezed to its
     * height, rather than that frame with the rest as a height margin.
     *
     * The phone centres the map's camera on the whole frame, margin included, so with a margin the
     * car sits on the rows that are cropped away and shows at the very bottom of the panel. Squeezed,
     * every row of the frame is on the panel, and the pixel aspect ratio has the phone draw it
     * stretched ahead so it shows undistorted. Null for a panel that is not wider than 16:9.
     */
    private fun squeezed(panelW: Int, panelH: Int, densityDpi: Int): Profile? {
        if (panelW.toLong() * 9 <= panelH.toLong() * 16) return null
        val wide = SIZES.filter { (_, size) -> size.first > size.second && size.first * 9 == size.second * 16 }
        val chosen = wide.filter { it.second.first >= panelW }.minByOrNull { it.second.first }
            ?: wide.maxByOrNull { it.second.first } ?: return null
        val (frameW, frameH) = chosen.second
        // (panel px per frame px across) / (panel px per frame px down)
        val par = (10000L * panelW * frameH / (frameW.toLong() * panelH)).toInt()
        return Profile(
            resolution = chosen.first,
            widthMargin = 0,
            heightMargin = 0,
            density = if (densityDpi in 1..640) densityDpi else 160,
            frameRate = FrameRate._30,
            pixelAspectRatioE4 = par,
        )
    }

    /**
     * Whether an output squeezes a wide panel's frame (see [squeezed]): only those that scale the
     * decoded picture onto a view do. A network receiver, a USB display or the MS912x crop the frame
     * from its top-left and need the margin layout.
     */
    fun squeezesWidePanels(output: SecondScreenOutputPolicy.Output): Boolean =
        output == SecondScreenOutputPolicy.Output.ANDROID_DISPLAY ||
            output == SecondScreenOutputPolicy.Output.TAPLO_APP

    /**
     * What the second sink is announced as. AUXILIARY honours our content choice; CLUSTER gets
     * whatever the phone decides an instrument cluster shows.
     */
    enum class Role { AUXILIARY, CLUSTER }

    fun roleOrDefault(stored: String?): Role = Role.values().firstOrNull { it.name == stored } ?: Role.AUXILIARY

    fun displayType(role: Role): Control.DisplayType = when (role) {
        Role.AUXILIARY -> Control.DisplayType.DISPLAY_TYPE_AUXILIARY
        Role.CLUSTER -> Control.DisplayType.DISPLAY_TYPE_CLUSTER
    }

    /** The phone ignores a content keycode on a cluster, so none is sent there. */
    fun announcesContent(role: Role): Boolean = role == Role.AUXILIARY

    /** The largest share of the panel either inset may claim, so some picture is always left. */
    const val MAX_INSET_PERCENT = 45

    /**
     * What the BAIC/Qinggan taplo hides at the top and bottom of its 1920x720 panel, so the taplo app
     * output starts from there rather than from nothing.
     */
    const val TAPLO_INSET_TOP_PERCENT = 15
    const val TAPLO_INSET_BOTTOM_PERCENT = 8

    /** Pixels Android Auto should keep its own UI out of, at the top and bottom of the panel. */
    data class ContentInsets(val top: Int, val bottom: Int, val left: Int = 0, val right: Int = 0) {
        val isEmpty: Boolean get() = top == 0 && bottom == 0 && left == 0 && right == 0
    }

    /**
     * Pixels of the frame to keep free on the right. On a wide panel the phone lays its map out as on
     * a landscape head unit, with the car at about four fifths of the width, so the car sits far to
     * the right; an inset there moves the car, and the turn card and arrival bar with it, towards
     * the middle, and leaves that strip to whatever the cluster draws itself. Measured on a
     * 1920x720 taplo: none puts the car at x 1540, 28% at x 1137, about 38% at the centre.
     */
    fun rightInset(pictureWidthPx: Int, percent: Int): Int =
        pictureWidthPx.coerceAtLeast(0) * percent.coerceIn(0, MAX_INSET_PERCENT) / 100

    /**
     * The part of the panel that is covered (a bezel, a gauge, an overlay drawn over the picture),
     * as content insets: unlike margins the phone still draws there, but it moves its turn card,
     * its arrival bar and the car marker into what is left.
     *
     * Android Auto lays its second screen out over the whole announced frame and does not keep its
     * UI out of the margins, so a height margin (1920x720 goes out as 1920x1080, 360 below) would
     * swallow the arrival bar it draws at the bottom. The margin is therefore added to the bottom
     * inset: the bar lands just above the covered part of the panel.
     */
    fun contentInsets(panelHeightPx: Int, topPercent: Int, bottomPercent: Int, heightMarginPx: Int = 0): ContentInsets {
        val height = panelHeightPx.coerceAtLeast(0)
        fun share(percent: Int) = height * percent.coerceIn(0, MAX_INSET_PERCENT) / 100
        return ContentInsets(share(topPercent), share(bottomPercent) + heightMarginPx.coerceAtLeast(0))
    }

    /** Whether a stored content choice is one the protocol allows on an auxiliary display. */
    fun contentKeycodeOrDefault(stored: Int): Int =
        if (stored == KEYCODE_TURN_CARD) KEYCODE_TURN_CARD else KEYCODE_NAVIGATION
}
