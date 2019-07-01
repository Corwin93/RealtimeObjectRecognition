package com.lance.realtimeobjectrecognition

import android.graphics.Bitmap
import io.reactivex.Observable

interface Classifier {
    fun classifyObjects(image: Bitmap): Observable<Pair<String, Float>>
}