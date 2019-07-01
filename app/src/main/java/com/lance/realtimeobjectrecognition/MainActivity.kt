package com.lance.realtimeobjectrecognition

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Camera
import android.net.ConnectivityManager
import android.os.Bundle
import android.renderscript.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabeler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import com.lance.realtimeobjectrecognition.Classifier as Classifier1


class MainActivity : AppCompatActivity() {

    companion object {
        val TAG = "MainActivity"
    }

    lateinit var activeCamera: Camera
    lateinit var preview: CameraPreview
    lateinit var labeler: FirebaseVisionImageLabeler
    lateinit var textToSpeech: TextToSpeech
    lateinit var sub: PublishSubject<ByteArray>
    lateinit var classifier: com.lance.realtimeobjectrecognition.Classifier
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
        labeler = if (!statusOnline()) {
            FirebaseVision.getInstance().onDeviceImageLabeler
        } else {
            FirebaseVision.getInstance().cloudImageLabeler
        }
        classifier = MLKitClassifier(labeler)
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
        return publisher
            .throttleLast(2000, TimeUnit.MILLISECONDS)  //frames are throttled not to overload CPU and RAM
            .subscribeOn(Schedulers.computation())
            .map {
                val bitmap = Bitmap.createBitmap(activeCamera.parameters.previewSize.width,
                    activeCamera.parameters.previewSize.height, Bitmap.Config.ARGB_8888)
                val bmData = renderScriptNV21ToRGBA888(this@MainActivity,
                    activeCamera.parameters.previewSize.width,
                    activeCamera.parameters.previewSize.height,
                    it)
                bmData.copyTo(bitmap)
                bitmap
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
        sub = PublishSubject.create()

        disposables.add(subscribeWithMLKitObserver(sub))
        activeCamera = Camera.open()
        activeCamera.setPreviewCallback { data, _ ->
            if (data != null) processByteArray(data)
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
    fun processByteArray(data: ByteArray) {
        sub.onNext(data)
    }

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
