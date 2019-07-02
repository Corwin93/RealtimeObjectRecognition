package com.lance.realtimeobjectrecognition.utils

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.*
import java.nio.ByteBuffer

fun renderScriptNV21ToRGBA888(context: Context, width: Int, height: Int, nv21: ByteArray): Allocation {
    val rs = RenderScript.create(context)
    val yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    val yuvType = Type.Builder(rs, Element.U8(rs)).setX(nv21.size)
    val initial = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)

    val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height)
    val out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

    initial.copyFrom(nv21)

    yuvToRgbIntrinsic.setInput(initial)
    yuvToRgbIntrinsic.forEach(out)
    return out
}

fun convertByteArrayToBitmap(context: Context, byteArray: ByteArray, width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val bmData = renderScriptNV21ToRGBA888(context, width, height, byteArray)
    bmData.copyTo(bitmap)
    return bitmap
}