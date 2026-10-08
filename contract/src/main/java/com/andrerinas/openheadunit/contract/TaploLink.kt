package com.andrerinas.openheadunit.contract

/**
 * The link between the head unit and its taplo companion app ("Android Auto Navi").
 *
 * The companion is what a launcher embeds on the instrument cluster (taplo). It binds to
 * [SERVICE_CLASS] in [HeadUnit.packageName] with a Messenger, hands over the Surface of its view,
 * and the head unit decodes the auxiliary Android Auto stream straight into it. The head unit
 * answers with [MSG_STATE] whenever the session or the second screen changes, so the companion can
 * tell the driver to connect a phone when there is nothing to show.
 *
 * Binding needs [PERMISSION], a signature permission: both apps have to be signed with one key.
 */
object TaploLink {
    const val SERVICE_CLASS = "com.andrerinas.openheadunit.secondscreen.taplo.TaploLinkService"
    const val PERMISSION = "${HeadUnit.packageName}.permission.TAPLO_LINK"

    /** Companion to head unit: start sending [MSG_STATE] to `Message.replyTo`. */
    const val MSG_REGISTER = 1

    /** Companion to head unit: stop sending state to `Message.replyTo`. */
    const val MSG_UNREGISTER = 2

    /** Companion to head unit: draw here. `data` carries [KEY_SURFACE], [KEY_WIDTH], [KEY_HEIGHT], [KEY_DENSITY_DPI]. */
    const val MSG_SURFACE = 3

    /** Companion to head unit: the surface handed over is about to be destroyed. */
    const val MSG_SURFACE_GONE = 4

    /**
     * Head unit to companion: `data` carries [KEY_CONNECTED], [KEY_SESSION], [KEY_SECOND_SCREEN],
     * [KEY_CROP_X], [KEY_CROP_Y], [KEY_INSET_TOP_PERCENT], [KEY_INSET_BOTTOM_PERCENT] and
     * [KEY_CARD_TEXT_PERCENT]. A client that only wants the state and [MSG_NAV] registers and never
     * sends a surface.
     */
    const val MSG_STATE = 10

    /**
     * Head unit to companion: the turn-by-turn guidance Android Auto sends, whenever it changes.
     * [KEY_NAV_ACTIVE] false means no route is being guided and every other key is absent. Numbers
     * that are not known are absent too, so read them with a default.
     */
    const val MSG_NAV = 11

    const val KEY_SURFACE = "surface"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_DENSITY_DPI = "density_dpi"

    /** An Android Auto session is up: [KEY_SESSION] is [SESSION_CONNECTED]. */
    const val KEY_CONNECTED = "connected"

    /** Where the session is: [SESSION_DISCONNECTED], [SESSION_CONNECTING] or [SESSION_CONNECTED]. */
    const val KEY_SESSION = "session"
    const val SESSION_DISCONNECTED = "disconnected"
    /** A phone is attached and the link is being set up; no picture or guidance yet. */
    const val SESSION_CONNECTING = "connecting"
    const val SESSION_CONNECTED = "connected"

    /** Text size for a guidance card drawn over the picture, in percent of its normal size. */
    const val KEY_CARD_TEXT_PERCENT = "card_text_percent"

    /** The head unit is set to send the second screen to this app, so a picture will come. */
    const val KEY_SECOND_SCREEN = "second_screen"

    /** How far to enlarge the picture from its top-left so the margin Android Auto leaves falls off. */
    const val KEY_CROP_X = "crop_x"
    const val KEY_CROP_Y = "crop_y"

    /** How much of the panel is covered at the top and bottom, in percent of its height. */
    const val KEY_INSET_TOP_PERCENT = "inset_top_percent"
    const val KEY_INSET_BOTTOM_PERCENT = "inset_bottom_percent"

    const val KEY_NAV_ACTIVE = "nav_active"
    /** The road the next manoeuvre leads onto. */
    const val KEY_NAV_ROAD = "nav_road"
    /** The manoeuvre in words, in the head unit's language. */
    const val KEY_NAV_ACTION = "nav_action"
    /** NextTurnDetail.NextEvent: 1 depart, 3 slight turn, 4 turn, 5 sharp, 6 U-turn, 11-13 roundabout, 14 straight, 19 destination... */
    const val KEY_NAV_EVENT = "nav_event"
    /** NextTurnDetail.Side: 1 left, 2 right, 3 unspecified. */
    const val KEY_NAV_SIDE = "nav_side"
    const val KEY_NAV_ROUNDABOUT_EXIT = "nav_roundabout_exit"
    const val KEY_NAV_DISTANCE_M = "nav_distance_m"
    const val KEY_NAV_TIME_S = "nav_time_s"
    const val KEY_NAV_TOTAL_DISTANCE_M = "nav_total_distance_m"
    const val KEY_NAV_TOTAL_TIME_S = "nav_total_time_s"
    /** Arrival time as the phone formats it, e.g. "16:07". */
    const val KEY_NAV_ETA = "nav_eta"
}
