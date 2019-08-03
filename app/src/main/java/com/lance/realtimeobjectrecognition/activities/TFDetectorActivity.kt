package com.lance.realtimeobjectrecognition.activities

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.toColor
import com.lance.realtimeobjectrecognition.R
import com.lance.realtimeobjectrecognition.tracking_classifiers.*
import com.lance.realtimeobjectrecognition.utils.*
import com.lance.realtimeobjectrecognition.views.BorderedText
import com.lance.realtimeobjectrecognition.views.OverlayView
import java.io.IOException
import java.util.*

class TFDetectorActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener,
Camera.PreviewCallback {

    companion object {
        const val TAG = "TFDetectorActivity"
        private const val TF_OD_API_INPUT_SIZE = 300
        private const val TF_OD_API_IS_QUANTIZED = true
        private const val TF_OD_API_MODEL_FILE = "detect.tflite"
        private const val TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt"
        private val MODE = DetectorMode.TF_OD_API

        private const val MINIMUM_CONFIDENCE_TF_OD_API = 0.5f
        private const val MAINTAIN_ASPECT = false
        private val DESIRED_PREVIEW_SIZE = Size(640, 480)
        private const val SAVE_PREVIEW_BITMAP = false
        private const val TEXT_SIZE_DIP = 10f
    }


    lateinit var trackingOverlay: OverlayView
    private var sensorOrientation: Int? = null

    private var detector: Classifier? = null

    private var lastProcessingTimeMs: Long = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var computingDetection = false

    private var timestamp: Long = 0

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var tracker: MultiBoxTracker? = null

    private var borderedText: BorderedText? = null

    private val PERMISSIONS_REQUEST = 1

    private val PERMISSION_CAMERA = Manifest.permission.CAMERA
    protected var previewWidth = 0
    protected var previewHeight = 0
    private val debug = false
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var useCamera2API: Boolean = false
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride: Int = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_tfdetector)

        textToSpeech = TextToSpeech(applicationContext, TextToSpeech.OnInitListener {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            } else {
                Toast.makeText(this, "Text-to-Speech failure", Toast.LENGTH_SHORT).show()
                Log.e(LabelerActivity.TAG, "Text-to-Speech failure")
            }
        })

        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }
    }

    protected fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    protected fun getLuminanceStride(): Int {
        return yRowStride
    }

    protected fun getLuminance(): ByteArray? {
        return yuvBytes[0]
    }

    /** Callback for android.hardware.Camera API  */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        if (isProcessingFrame) {
            return
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                val previewSize = camera.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            return
        }

        isProcessingFrame = true
        yuvBytes[0] = bytes
        yRowStride = previewWidth

        imageConverter = Runnable { convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes!!) }

        postInferenceCallback = Runnable {
            camera.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    /** Callback for Camera2 API  */
    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return

            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable {
                convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }

            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }

            processImage()
        } catch (e: Exception) {
            Trace.endSection()
            return
        }

        Trace.endSection()
    }


    override fun onResume() {
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    override fun onPause() {

        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.message)
        }

        super.onPause()
    }

    @Synchronized
    protected fun runInBackground(r: () -> Unit) {
        if (handler != null) {
            handler!!.post(r)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.size > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && grantResults[1] == PackageManager.PERMISSION_GRANTED
            ) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(PERMISSION_CAMERA) === PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                    this@TFDetectorActivity,
                    "Camera permission is required for this demo",
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA), PERMISSIONS_REQUEST)
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private fun isHardwareLevelSupported(
        characteristics: CameraCharacteristics, requiredLevel: Int
    ): Boolean {
        val deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = facing == CameraCharacteristics.LENS_FACING_EXTERNAL || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                )
                return cameraId
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Not allowed to access camera")
        }

        return null
    }

    protected fun setFragment() {
        val cameraId = chooseCamera()

        val fragment: Fragment
        if (useCamera2API) {
            val camera2Fragment = CameraConnectionFragment.newInstance(
                object : CameraConnectionFragment.ConnectionCallback {
                    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
                        previewHeight = size.height
                        previewWidth = size.width
                        this@TFDetectorActivity.onPreviewSizeChosen(size, rotation)
                    }
                },
                this,
                getLayoutId(),
                getDesiredPreviewFrameSize()
            )

            camera2Fragment.setCamera(cameraId!!)
            fragment = camera2Fragment
        } else {
            fragment = LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize())
        }

        fragmentManager.beginTransaction().replace(R.id.previewContainer, fragment).commit()
    }

    protected fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    fun isDebug(): Boolean {
        return debug
    }

    protected fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }

    protected fun getScreenOrientation(): Int {
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> return 270
            Surface.ROTATION_180 -> return 180
            Surface.ROTATION_90 -> return 90
            else -> return 0
        }
    }

    fun onPreviewSizeChosen(size:Size, rotation:Int) {
        val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)
        tracker = MultiBoxTracker(this)
        var cropSize = TF_OD_API_INPUT_SIZE
        try {
            detector = TFLiteObjectDetectionAPIModel.create(assets, TF_OD_API_MODEL_FILE, TF_OD_API_LABELS_FILE,
                TF_OD_API_INPUT_SIZE, TF_OD_API_IS_QUANTIZED)
            cropSize = TF_OD_API_INPUT_SIZE
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, e.message)
            val toast = Toast.makeText(applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT)
            toast.show()
            finish()
        }
        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - getScreenOrientation()
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

        frameToCropTransform = getTransformationMatrix(previewWidth, previewHeight, cropSize,
            cropSize, sensorOrientation!!, MAINTAIN_ASPECT)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay) as OverlayView
        trackingOverlay.callbacks.add(object:OverlayView.OverlayCallback {
            override fun draw(canvas: Canvas) {
                tracker!!.draw(canvas)
                if (isDebug()) {
                    tracker!!.drawDebug(canvas)
                }
            }
        })

        tracker!!.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation!!)
    }

    protected fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        trackingOverlay.postInvalidate()

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage()
            return
        }
        computingDetection = true

        rgbFrameBitmap!!.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight)

        readyForNextImage()

        val canvas = Canvas(croppedBitmap)
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null)
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            saveBitmap(croppedBitmap!!)
        }

        runInBackground {
            val startTime = SystemClock.uptimeMillis()
            val results = detector!!.recognizeImage(croppedBitmap!!)
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap)
            val canvas = Canvas(cropCopyBitmap)
            val paint = Paint()
            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f
            var minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API

            when (MODE) {
                DetectorMode.TF_OD_API -> minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API
            }

            val mappedRecognitions = LinkedList<Classifier.Recognition>()

            for (result in results) {
                val location = result.getLocation()
                if (location != null && result.confidence!! >= minimumConfidence) {
                    //Experimental
                    Log.d(TAG, " Recognized location left = " + location.left + " " + location.left.toInt() +
                            " Location top = " + location.top + " " + location.top.toInt() + " Location width = " +
                            location.width() + " " + location.width().toInt() + " Location height = " +
                            location.height() + " " + location.height().toInt())
                    val cropAreaLeft = if (location.left < 0) 0 else location.left.toInt()
                    val cropAreaTop = if (location.top < 0) 0 else location.top.toInt()
                    val cropAreaWidth = if (cropAreaLeft + location.width() > croppedBitmap!!.width) croppedBitmap!!.width - cropAreaLeft else location.width().toInt()
                    val cropAreaHeight = if (cropAreaTop + location.height() > croppedBitmap!!.height) croppedBitmap!!.height - cropAreaTop else location.height().toInt()

                    val expBitmap = Bitmap.createBitmap(croppedBitmap!!, cropAreaLeft, cropAreaTop, cropAreaWidth, cropAreaHeight, null, false)
                    val scaledBitmap = Bitmap.createScaledBitmap(expBitmap, 1, 1, false)
                    val primaryIntColor = scaledBitmap.getPixel(0, 0)
                    val identifiedColor = identifyColor(primaryIntColor)

                    Log.d(TAG, identifiedColor.title + " " + result.title)
                    result.title = identifiedColor.title+ " " + result.title
                    val speechData = result.title
                    val speechStatus = textToSpeech.speak(speechData, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
                    if (speechStatus == TextToSpeech.ERROR) {
                        Log.e(LabelerActivity.TAG, "Error while translating text to speech")
                    }
                    //Experimental-end

                    canvas.drawRect(location!!, paint)
                    cropToFrameTransform!!.mapRect(location)
                    result.setLocation(location)
                    mappedRecognitions.add(result)
                }
            }

            tracker!!.trackResults(mappedRecognitions, currTimestamp)
            trackingOverlay.postInvalidate()

            computingDetection = false
        }
    }

    protected fun getLayoutId():Int {
        return R.layout.camera_connection_fragment_tracking
    }

    protected fun getDesiredPreviewFrameSize():Size {
        return DESIRED_PREVIEW_SIZE
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum class DetectorMode {
        TF_OD_API
    }

    protected fun setUseNNAPI(isChecked:Boolean) {
        runInBackground { detector!!.setUseNNAPI(isChecked) }
    }

    protected fun setNumThreads(numThreads:Int) {
        runInBackground { detector!!.setNumThreads(numThreads) }
    }
}
