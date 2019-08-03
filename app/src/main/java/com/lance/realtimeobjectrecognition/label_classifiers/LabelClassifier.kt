package com.lance.realtimeobjectrecognition.label_classifiers

import android.graphics.Bitmap
import io.reactivex.Observable

interface LabelClassifier {
    fun classifyObjects(image: Bitmap): Observable<Pair<String, Float>>
}