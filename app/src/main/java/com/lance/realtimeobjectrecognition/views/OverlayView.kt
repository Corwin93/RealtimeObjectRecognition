package com.lance.realtimeobjectrecognition.views

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

class OverlayView(val contextParam: Context, val attrs: AttributeSet): View(contextParam, attrs) {
    val callbacks = LinkedList<OverlayCallback>()

    override fun draw(canvas: Canvas?) {
        super.draw(canvas)
        if (canvas != null) callbacks.forEach { it.draw(canvas) }
    }

    interface OverlayCallback {
        fun draw(canvas: Canvas)
    }
}