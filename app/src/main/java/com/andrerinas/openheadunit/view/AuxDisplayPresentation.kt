package com.andrerinas.openheadunit.view

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup

/**
 * The auxiliary display's window when it is hosted by the projection activity, which is what this
 * falls back to without the overlay permission. See [AuxPictureView] for the picture itself.
 */
internal class AuxDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val createPicture: (Context) -> AuxPictureView,
) : Presentation(outerContext, display) {

    private var picture: AuxPictureView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = createPicture(context)
        picture = view
        setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onStop() {
        picture?.release("the auxiliary display was dismissed")
        super.onStop()
    }
}
