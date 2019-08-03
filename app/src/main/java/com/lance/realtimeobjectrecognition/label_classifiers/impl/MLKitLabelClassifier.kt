package com.lance.realtimeobjectrecognition.label_classifiers.impl

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabeler
import com.lance.realtimeobjectrecognition.label_classifiers.LabelClassifier
import io.reactivex.Observable
import java.lang.Exception

class MLKitLabelClassifier(val labeler: FirebaseVisionImageLabeler):
    LabelClassifier {

    /**
     * Processes bitmap image using given [labeler]. As a result, produces
     * the ordered stream of pairs title-confidence for recognized objects
     * beginning with most probable predictions.
     *
     * @return observable producing title-confidence pairs
     *
     * @param image original bitmap
     */
    override fun classifyObjects(image: Bitmap): Observable<Pair<String, Float>> {
        val preparedImage = FirebaseVisionImage.fromBitmap(image)
        var labels = " "
        return Observable.create<Pair<String, Float>> {emitter ->
            labeler.processImage(preparedImage).addOnSuccessListener { labelList ->
                if (labelList.isNotEmpty()) {
                    labelList.forEach {
                        emitter.onNext(Pair(it.text, it.confidence))
                        labels += it.text + " " + it.confidence + "; "
                    }
                    Log.d("Labels: ", labels)
                }
                image.recycle()
            }
                .addOnFailureListener {
                    emitter.onError(Exception(it.message))
                    image.recycle()
                }
        }
    }
}