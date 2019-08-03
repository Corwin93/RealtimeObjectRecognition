package com.lance.realtimeobjectrecognition.tracking_classifiers

import android.app.Activity
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.Build
import android.util.Log
import android.util.SparseIntArray
import android.view.Surface
import androidx.annotation.RequiresApi
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.objects.FirebaseVisionObject
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetector
import com.google.firebase.ml.vision.objects.FirebaseVisionObjectDetectorOptions
import io.reactivex.Observable
import java.lang.Exception

class MLKitTracker(val options: FirebaseVisionObjectDetectorOptions) {
    companion object {
        const val TAG = "MLKitTracker"
    }

    private val objectDetector: FirebaseVisionObjectDetector

    init {
        objectDetector = FirebaseVision.getInstance().getOnDeviceObjectDetector(options)
    }

    fun detect(byteArray: ByteArray, cameraId: String, width: Int, height: Int, activity: Activity, context: Context): Observable<FirebaseVisionObject> {
        val rotation = getRotationCompensation(cameraId, activity, context)
        val metadata = FirebaseVisionImageMetadata.Builder()
            .setWidth(width)
            .setHeight(height)
            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
            .setRotation(rotation)
            .build()
        val image = FirebaseVisionImage.fromByteArray(byteArray, metadata)
        return Observable.create { emitter ->
            objectDetector.processImage(image)
                .addOnSuccessListener { detectedObjects ->
                    if (detectedObjects.isNotEmpty()) {
                        detectedObjects.forEach {
                            emitter.onNext(it)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, e.message)
                }
        }
    }

    private val ORIENTATIONS = SparseIntArray()

    init {
        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
    }
    /**
     * Get the angle by which an image must be rotated given the device's current
     * orientation.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Throws(CameraAccessException::class)
    private fun getRotationCompensation(cameraId: String, activity: Activity, context: Context): Int {
        // Get the device's current rotation relative to its "native" orientation.
        // Then, from the ORIENTATIONS table, look up the angle the image must be
        // rotated to compensate for the device's rotation.
        val deviceRotation = activity.windowManager.defaultDisplay.rotation
        var rotationCompensation = ORIENTATIONS.get(deviceRotation)

        // On most devices, the sensor orientation is 90 degrees, but for some
        // devices it is 270 degrees. For devices with a sensor orientation of
        // 270, rotate the image an additional 180 ((270 + 270) % 360) degrees.
        val cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
        val sensorOrientation = cameraManager
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        rotationCompensation = (rotationCompensation + sensorOrientation + 270) % 360

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        val result: Int
        when (rotationCompensation) {
            0 -> result = FirebaseVisionImageMetadata.ROTATION_0
            90 -> result = FirebaseVisionImageMetadata.ROTATION_90
            180 -> result = FirebaseVisionImageMetadata.ROTATION_180
            270 -> result = FirebaseVisionImageMetadata.ROTATION_270
            else -> {
                result = FirebaseVisionImageMetadata.ROTATION_0
                Log.e(TAG, "Bad rotation value: $rotationCompensation")
            }
        }
        return result
    }

}