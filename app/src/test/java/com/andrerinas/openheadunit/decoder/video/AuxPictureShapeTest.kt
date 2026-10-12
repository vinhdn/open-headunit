package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.secondscreen.SecondScreenOutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

private typealias Resolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

class AuxPictureShapeTest {

    @Test
    fun `3 by 2 on the 1920x720 taplo is 1080x720 in the middle`() {
        val shape = AuxPictureShape.of("3:2")
        assertEquals(1080 to 720, shape.announcedSize(1920, 720))
        assertEquals(AuxPictureShape.Rect(420, 0, 1080, 720), shape.placement(1920, 720))
        assertEquals(1.5f, shape.aspect())
    }

    @Test
    fun `a resolution is announced as is and placed scaled to fit`() {
        val shape = AuxPictureShape.of("1280x720")
        assertEquals(1280 to 720, shape.announcedSize(1920, 720))
        assertEquals(AuxPictureShape.Rect(320, 0, 1280, 720), shape.placement(1920, 720))
        assertEquals(AuxPictureShape.Rect(0, 75, 800, 450), shape.placement(800, 600))
    }

    @Test
    fun `an unknown or missing choice fills the panel`() {
        assertEquals(AuxPictureShape.Fill, AuxPictureShape.of(null))
        assertEquals(AuxPictureShape.Fill, AuxPictureShape.of("21:9"))
        assertEquals(1920 to 720, AuxPictureShape.Fill.announcedSize(1920, 720))
        assertEquals(0f, AuxPictureShape.Fill.aspect())
    }

    @Test
    fun `a 3 by 2 picture is squeezed onto a 1280x720 frame, not given a margin`() {
        val plan = AuxDisplayProfilePolicy.plan(1920, 720, 160, SecondScreenOutputPolicy.Output.TAPLO_APP, "3:2")
        assertEquals(Resolution._1280x720, plan.profile.resolution)
        assertEquals(0, plan.profile.widthMargin)
        assertEquals(8437, plan.profile.pixelAspectRatioE4)
        assertEquals(1f to 1f, AuxDisplayProfilePolicy.marginCropScale(plan.profile))
    }

    @Test
    fun `outputs that crop the frame always fill`() {
        val plan = AuxDisplayProfilePolicy.plan(1920, 720, 160, SecondScreenOutputPolicy.Output.NETWORK, "3:2")
        assertEquals(AuxPictureShape.Fill, plan.shape)
        assertEquals(1920 to 720, plan.announcedWidth to plan.announcedHeight)
    }

    @Test
    fun `covers reach a centred picture only as far as they overlap it`() {
        // A full-height picture shares the whole cover, in the frame's rows.
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(108, 57),
            AuxDisplayProfilePolicy.pictureInsets(720, 0, 720, 720, 15, 8))
        // A 16:9 picture 450 tall in the middle of 600 sits 75 down: a 15% cover (90) reaches 15 in.
        assertEquals(AuxDisplayProfilePolicy.ContentInsets(24, 0),
            AuxDisplayProfilePolicy.pictureInsets(600, 75, 450, 720, 15, 8))
    }
}
