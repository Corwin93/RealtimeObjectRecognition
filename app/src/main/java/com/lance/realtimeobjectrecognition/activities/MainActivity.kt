package com.lance.realtimeobjectrecognition.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.ml.vision.FirebaseVision
import com.lance.realtimeobjectrecognition.R
import com.lance.realtimeobjectrecognition.classifiers.impl.MLKitClassifier
import com.lance.realtimeobjectrecognition.classifiers.impl.TensorFlowClassifier
import com.lance.realtimeobjectrecognition.utils.convertByteArrayToBitmap
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import com.lance.realtimeobjectrecognition.classifiers.Classifier as Classifier1


class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = "MainActivity"
        val CAMERA_PERMISSION_REQUEST = 0
        val CAMERA_PERMISSION_REQUEST_FROM_SETTINGS = 1
    }

    lateinit var activeCamera: Camera
    lateinit var preview: CameraPreview
    lateinit var textToSpeech: TextToSpeech
    lateinit var sub: PublishSubject<ByteArray>
    var disposables = CompositeDisposable()

    @SuppressLint("CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textToSpeech = TextToSpeech(applicationContext, TextToSpeech.OnInitListener {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            } else {
                Toast.makeText(this, "Text-to-Speech failure", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Text-to-Speech failure")
            }
        })
    }

    /**
     * Processes stream of byte arrays representing image frames wrapped in a PublishSubject [publisher].
     * Takes byte array from the input every 2 seconds. Byte array is converted into bitmap.
     * Bitmap is processed with MLKit object [classifier]. Finally classifier returns object titles
     * and recognition confidence. Several most probable labels are displayed on the screen and announced
     * with text-to-speech.
     *
     * @return Disposable which is disposed when the activity is paused
     *
     * @param publisher original stream of frames presented as ByteArray
     */
    private fun subscribeWithMLKitObserver(publisher: PublishSubject<ByteArray>): Disposable {
        val totalNumberOfLabels = 20
        val labeler = if (!statusOnline()) {
            FirebaseVision.getInstance().onDeviceImageLabeler
        } else {
            FirebaseVision.getInstance().cloudImageLabeler
        }
        val classifier = MLKitClassifier(labeler)
        return publisher
            .throttleLast(2000, TimeUnit.MILLISECONDS)  //frames are throttled not to overload CPU and RAM
            .subscribeOn(Schedulers.computation())
            .map {
                convertByteArrayToBitmap(this, it,
                    activeCamera.parameters.previewSize.width, activeCamera.parameters.previewSize.height)
            }
            .observeOn(Schedulers.io())
            .flatMap {
                classifier.classifyObjects(it)
            }
            //whole stream is divided into segments according to the number of labels returned by the classifier
            .buffer(totalNumberOfLabels)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe ({labelList: List<Pair<String, Float>> ->
                var speechData = ""
                var textViewData = ""
                //n most probable labels are displayed and announced
                labelList.take(2).forEach {labelPair ->
                    speechData += labelPair.first+" "
                    textViewData += labelPair.first + " " + labelPair.second.toString() + " "
                }
                tvMain.text = textViewData
                val speechStatus = textToSpeech.speak(speechData, TextToSpeech.QUEUE_FLUSH, null, textViewData)
                if (speechStatus == TextToSpeech.ERROR) {
                    Log.e(TAG, "Error while translating text to speech")
                }
            }, {
                Log.e(TAG, it.message)
            }, {
                Log.d(TAG, "Frame capture complete.")
            })
    }

    /**
     * Processes stream of byte arrays representing image frames wrapped in a PublishSubject [publisher].
     * Takes byte array from the input every 2 seconds. Byte array is converted into bitmap.
     * Bitmap is processed with MLKit object [classifier]. Finally classifier returns object titles
     * and recognition confidence. Several most probable labels are displayed on the screen and announced
     * with text-to-speech.
     *
     * @return Disposable which is disposed when the activity is paused
     *
     * @param publisher original stream of frames presented as ByteArray
     */
    private fun subscribeWithTensorFlowObserver(publisher: PublishSubject<ByteArray>): Disposable {
        val totalNumberOfLabels = 20
        val modelPath = "mobilenet_v1_1.0_224_quant.tflite"
        val labelPath = "labels.txt"
        val inputSize = 224
        val quant = true
        val classifier = TensorFlowClassifier(applicationContext.assets, modelPath, labelPath, inputSize, quant)
        return publisher
            .throttleLast(2000, TimeUnit.MILLISECONDS)  //frames are throttled not to overload CPU and RAM
            .subscribeOn(Schedulers.computation())
            .map {
                convertByteArrayToBitmap(this, it,
                    activeCamera.parameters.previewSize.width, activeCamera.parameters.previewSize.height)
            }
            .observeOn(Schedulers.io())
            .flatMap {
                classifier.classifyObjects(it)
            }
            //whole stream is divided into segments according to the number of labels returned by the classifier
            .buffer(totalNumberOfLabels)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe ({labelList: List<Pair<String, Float>> ->
                var speechData = ""
                var textViewData = ""
                //n most probable labels are displayed and announced
                labelList.take(2).forEach {labelPair ->
                    speechData += labelPair.first+" "
                    textViewData += labelPair.first + " " + labelPair.second.toString() + " "
                }
                tvMain.text = textViewData
                val speechStatus = textToSpeech.speak(speechData, TextToSpeech.QUEUE_FLUSH, null, textViewData)
                if (speechStatus == TextToSpeech.ERROR) {
                    Log.e(TAG, "Error while translating text to speech")
                }
            }, {
                Log.e(TAG, it.message)
            }, {
                Log.d(TAG, "Frame capture complete.")
            })
    }

    override fun onResume() {
        super.onResume()
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                Snackbar.make(previewContainer, R.string.camera_rationale, Snackbar.LENGTH_LONG)
                    .setAction("GRANT") {
                        requestPermissions(Array<String>(1){Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST)
                    }.show()
            } else {
                requestPermissions(Array(1) { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST)
            }
        } else {
            setupCameraPreview()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.size > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCameraPreview()
            } else {
                onCameraPermissionDenied()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun onCameraPermissionDenied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                Snackbar.make(previewContainer, R.string.camera_rationale, Snackbar.LENGTH_LONG)
                    .setAction("GRANT") {
                        requestPermissions(Array<String>(1){Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST)
                    }.show()
            } else {
                Snackbar.make(previewContainer, R.string.camera_never_ask_again_warning, Snackbar.LENGTH_LONG)
                    .setAction("SETTINGS") {
                        openAppSettings()
                    }.show()
                Toast.makeText(applicationContext, R.string.camera_permission_settings_instruction, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivityForResult(intent, CAMERA_PERMISSION_REQUEST_FROM_SETTINGS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_FROM_SETTINGS) {
            checkCameraPermission()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setupCameraPreview() {
        sub = PublishSubject.create()
        disposables.add(subscribeWithMLKitObserver(sub))
        activeCamera = Camera.open()
        activeCamera.setPreviewCallback { data, _ ->
            if (data != null) processFrame(data)
        }
        preview = CameraPreview(this, activeCamera)
        previewContainer.addView(preview)
    }

    override fun onPause() {
        super.onPause()
        sub.onComplete()
        disposables.clear()
        activeCamera.stopPreview()
        activeCamera.release()
    }


    /**
     * Send [data] to the reactive publisher [sub]
     */
    fun processFrame(data: ByteArray) {
        sub.onNext(data)
    }

    fun statusOnline(): Boolean {
        val networkManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (networkManager.activeNetworkInfo == null || !networkManager.activeNetworkInfo.isConnected) {
            Toast.makeText(applicationContext, "Offline mode produces less accurate results", Toast.LENGTH_LONG).show()
            return false
        } else {
            return true
        }
    }

    class CameraPreview(
        context: Context,
        val camera: Camera
    ) : SurfaceView(context), SurfaceHolder.Callback {
        private val mHolder: SurfaceHolder = holder.apply {
            addCallback(this@CameraPreview)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            camera.apply {
                try {
                    setPreviewDisplay(holder)
                    startPreview()
                } catch (e: IOException) {
                    Log.d(TAG, "Error setting activeCamera preview: ${e.message}")
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            holder.removeCallback(this)
//            camera.stopPreview()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            if (mHolder.surface == null) {
                return
            }
            try {
                camera.stopPreview()
            } catch (e: Exception) {
            }
            camera.apply {
                try {
                    setPreviewDisplay(mHolder)
                    startPreview()
                } catch (e: Exception) {
                    Log.d(TAG, "Error starting activeCamera preview: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (textToSpeech != null) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
}
