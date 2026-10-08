package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

class AuxDisplayProfilePolicyTest {

    @Test
    fun `a panel that matches a standard size exactly takes it with no margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(800, 480, 160)
        assertEquals(Resolution._800x480, profile.resolution)
        assertEquals(0, profile.widthMargin)
        assertEquals(0, profile.heightMargin)
    }

    @Test
    fun `an odd cluster panel takes the smallest size that contains it, and the rest is margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(1024, 600, 160)
        assertEquals(Resolution._1280x720, profile.resolution)
        assertEquals(1280 - 1024, profile.widthMargin)
        assertEquals(720 - 600, profile.heightMargin)
    }

    @Test
    fun `a tall panel takes a portrait size rather than being rotated into a landscape one`() {
        val profile = AuxDisplayProfilePolicy.profileFor(600, 1024, 160)
        assertEquals(Resolution._720x1280, profile.resolution)
        assertEquals(720 - 600, profile.widthMargin)
        assertEquals(1280 - 1024, profile.heightMargin)
    }

    @Test
    fun `a panel larger than anything on offer takes the largest and no margin`() {
        val profile = AuxDisplayProfilePolicy.profileFor(3840, 2160, 320)
        assertEquals(0, profile.widthMargin)
        assertEquals(0, profile.heightMargin)
    }

    @Test
    fun `an unreadable density is replaced rather than announced as zero`() {
        assertEquals(160, AuxDisplayProfilePolicy.profileFor(800, 480, 0).density)
        assertEquals(160, AuxDisplayProfilePolicy.profileFor(800, 480, -1).density)
        assertEquals(213, AuxDisplayProfilePolicy.profileFor(800, 480, 213).density)
    }

    @Test
    fun `a zero-sized panel is treated as one pixel rather than dividing by nothing`() {
        val profile = AuxDisplayProfilePolicy.profileFor(0, 0, 160)
        assertEquals(Resolution._800x480, profile.resolution)
    }

    @Test
    fun `the auxiliary stream is announced at 30fps`() {
        val thirty = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
        assertEquals(thirty, AuxDisplayProfilePolicy.profileFor(1024, 600, 160).frameRate)
    }

    @Test
    fun `only the two keycodes the protocol allows can be stored`() {
        assertEquals(
            AuxDisplayProfilePolicy.KEYCODE_TURN_CARD,
            AuxDisplayProfilePolicy.contentKeycodeOrDefault(AuxDisplayProfilePolicy.KEYCODE_TURN_CARD),
        )
        assertEquals(
            AuxDisplayProfilePolicy.KEYCODE_NAVIGATION,
            AuxDisplayProfilePolicy.contentKeycodeOrDefault(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION),
        )
        assertEquals(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION, AuxDisplayProfilePolicy.contentKeycodeOrDefault(0))
        assertEquals(AuxDisplayProfilePolicy.KEYCODE_NAVIGATION, AuxDisplayProfilePolicy.contentKeycodeOrDefault(65537))
    }

    @Test
    fun `a missing or unknown stored role reads as auxiliary`() {
        assertEquals(AuxDisplayProfilePolicy.Role.AUXILIARY, AuxDisplayProfilePolicy.roleOrDefault(null))
        assertEquals(AuxDisplayProfilePolicy.Role.AUXILIARY, AuxDisplayProfilePolicy.roleOrDefault("PASSENGER"))
        assertEquals(AuxDisplayProfilePolicy.Role.CLUSTER, AuxDisplayProfilePolicy.roleOrDefault("CLUSTER"))
    }

    @Test
    fun `each role goes out as its own display type and only auxiliary names its content`() {
        assertEquals(Control.DisplayType.DISPLAY_TYPE_AUXILIARY, AuxDisplayProfilePolicy.displayType(AuxDisplayProfilePolicy.Role.AUXILIARY))
        assertEquals(Control.DisplayType.DISPLAY_TYPE_CLUSTER, AuxDisplayProfilePolicy.displayType(AuxDisplayProfilePolicy.Role.CLUSTER))
        assertEquals(true, AuxDisplayProfilePolicy.announcesContent(AuxDisplayProfilePolicy.Role.AUXILIARY))
        assertEquals(false, AuxDisplayProfilePolicy.announcesContent(AuxDisplayProfilePolicy.Role.CLUSTER))
    }

    @Test
    fun `the margin crop enlarges the frame so only the picture fills the panel`() {
        val onPanel = AuxDisplayProfilePolicy.profileFor(1024, 600, 160)
        assertEquals(1.25f to 1.2f, AuxDisplayProfilePolicy.marginCropScale(onPanel))
        assertEquals(1f to 1f, AuxDisplayProfilePolicy.marginCropScale(AuxDisplayProfilePolicy.profileFor(1280, 720, 213)))
    }

    @Test
    fun `the height margin is added to the bottom inset, so the arrival bar stays on the panel`() {
        val profile = AuxDisplayProfilePolicy.profileFor(1920, 720, 160)
        assertEquals(360, profile.heightMargin)
        val insets = AuxDisplayProfilePolicy.contentInsets(720, 15, 8, heightMarginPx = profile.heightMargin)
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(108, 57 + 360), insets)
    }

    @Test
    fun `content insets are shares of the panel height`() {
        val insets = AuxDisplayProfilePolicy.contentInsets(720, topPercent = 20, bottomPercent = 10)
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(144, 72), insets)
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(96, 48), AuxDisplayProfilePolicy.contentInsets(480, 20, 10))
    }

    @Test
    fun `content insets never claim the whole panel and are empty by default`() {
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(324, 0), AuxDisplayProfilePolicy.contentInsets(720, 90, -5))
        assertTrue(AuxDisplayProfilePolicy.contentInsets(720, 0, 0).isEmpty)
    }

    @Test
    fun `a panel wider than 16 by 9 is squeezed onto a whole 16 by 9 frame`() {
        val profile = AuxDisplayProfilePolicy.profileFor(1920, 720, 160, squeezeWide = true)
        assertEquals(AuxDisplayProfilePolicy.Profile(
            resolution = Resolution._1920x1080, widthMargin = 0, heightMargin = 0, density = 160,
            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30,
            pixelAspectRatioE4 = 15000,
        ), profile)
        assertEquals(1080, profile.pictureHeightPx)
        assertEquals(1f to 1f, AuxDisplayProfilePolicy.marginCropScale(profile))
    }

    @Test
    fun `squeezing leaves panels up to 16 by 9 and the cropping outputs on margins`() {
        assertEquals(10000, AuxDisplayProfilePolicy.profileFor(800, 480, 160, squeezeWide = true).pixelAspectRatioE4)
        assertEquals(10000, AuxDisplayProfilePolicy.profileFor(1280, 720, 160, squeezeWide = true).pixelAspectRatioE4)
        val cropped = AuxDisplayProfilePolicy.profileFor(1920, 720, 160)
        assertEquals(360, cropped.heightMargin)
        assertEquals(720, cropped.pictureHeightPx)
        assertTrue(AuxDisplayProfilePolicy.squeezesWidePanels(SecondScreenOutputPolicy.Output.TAPLO_APP))
        assertFalse(AuxDisplayProfilePolicy.squeezesWidePanels(SecondScreenOutputPolicy.Output.MS912X))
    }

    @Test
    fun `the right inset is a share of the frame width, capped`() {
        assertEquals(537, AuxDisplayProfilePolicy.rightInset(1920, 28))
        assertEquals(0, AuxDisplayProfilePolicy.rightInset(1920, 0))
        assertEquals(864, AuxDisplayProfilePolicy.rightInset(1920, 90))
        assertTrue(AuxDisplayProfilePolicy.ContentInsets(0, 0, right = 0).isEmpty)
    }
}
