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

    /** Head unit to companion: `data` carries [KEY_CONNECTED], [KEY_SECOND_SCREEN], [KEY_CROP_X], [KEY_CROP_Y]. */
    const val MSG_STATE = 10

    const val KEY_SURFACE = "surface"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_DENSITY_DPI = "density_dpi"

    /** An Android Auto session is up. */
    const val KEY_CONNECTED = "connected"

    /** The head unit is set to send the second screen to this app, so a picture will come. */
    const val KEY_SECOND_SCREEN = "second_screen"

    /** How far to enlarge the picture from its top-left so the margin Android Auto leaves falls off. */
    const val KEY_CROP_X = "crop_x"
    const val KEY_CROP_Y = "crop_y"
}
