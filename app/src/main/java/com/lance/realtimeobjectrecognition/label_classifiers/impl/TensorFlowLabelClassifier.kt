package com.lance.realtimeobjectrecognition.label_classifiers.impl

import android.content.res.AssetManager
import android.graphics.Bitmap
import com.lance.realtimeobjectrecognition.label_classifiers.LabelClassifier
import io.reactivex.Observable
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.experimental.and


class TensorFlowLabelClassifier(val assetManager: AssetManager, val modelPath: String,
                                val labelPath: String, val inputSize: Int, val quant: Boolean) : LabelClassifier {


    companion object {
        val MAX_RESULTS = 3
        val BATCH_SIZE = 1
        val PIXEL_SIZE = 3
        val THRESHOLD = 0.1f
        val IMAGE_MEAN = 128
        val IMAGE_STD = 128.0f
    }

    val interpreter: Interpreter
    val labelList: List<String>

    init {
        interpreter = Interpreter(loadModelFile(assetManager, modelPath), Interpreter.Options())
        labelList = loadLabelList(assetManager, labelPath)
    }

    override fun classifyObjects(image: Bitmap): Observable<Pair<String, Float>> {
        val byteBuffer = convertBitmapToByteBuffer(image)
        val resultList: List<Pair<String, Float>>
        resultList = if (quant) {
            val result = Array(1) { ByteArray(labelList.size) }
            interpreter.run(byteBuffer, result)
            getSortedResultByte(result)
        } else {
            val result = Array(1) {FloatArray(labelList.size)}
            interpreter.run(byteBuffer, result)
            getSortedResultFloat(result)
        }
        return Observable.fromIterable(resultList).take(20)
    }

    fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        var byteBuffer: ByteBuffer
        if (quant) {
            byteBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * inputSize * inputSize * PIXEL_SIZE)
        } else {
            byteBuffer = ByteBuffer.allocateDirect(4 * BATCH_SIZE * inputSize * inputSize * PIXEL_SIZE)
        }
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val temp = intValues[pixel++]
                if (quant) {
                    byteBuffer.put(((temp shr 16) and 0xff).toByte())
                    byteBuffer.put(((temp shr 8) and 0xff).toByte())
                    byteBuffer.put((temp and 0xff).toByte())
                } else {
                    byteBuffer.putFloat((((temp shr 16) and 0xff)-IMAGE_MEAN)/ IMAGE_STD)
                    byteBuffer.putFloat((((temp shr 8) and 0xff)-IMAGE_MEAN)/ IMAGE_STD)
                    byteBuffer.putFloat(((temp and 0xff)-IMAGE_MEAN)/ IMAGE_STD)
                }
            }
        }
        return byteBuffer
    }

    private fun getSortedResultByte(labelProbArray: Array<ByteArray>): List<Pair<String, Float>> {
        val resultList = LinkedList<Pair<String, Float>>()
        for (i in 0 until labelList.size) {
            val confidence = (labelProbArray[0][i] and 0xff.toByte()) / 255.0f
            if (confidence > THRESHOLD) {
                resultList.add(Pair(labelList[i], confidence))
            }
        }
        return resultList
    }

    private fun getSortedResultFloat(labelProbArray: Array<FloatArray>): List<Pair<String, Float>> {
        val resultList = LinkedList<Pair<String, Float>>()
        for (i in 0 until labelList.size) {
            val confidence = labelProbArray[0][i]
            if (confidence > THRESHOLD) {
                resultList.add(Pair(labelList[i], confidence))
            }
        }
        return resultList
    }

    private fun loadLabelList(assetManager: AssetManager, labelPath: String): List<String> {
        val labelList = LinkedList<String>()
        val reader = BufferedReader(InputStreamReader(assetManager.open(labelPath)))
        var line = ""
        while (true) {
            line = reader.readLine() ?: ""
            if (line != null) {
                labelList.add(line)
            } else {
                break
            }
        }
        reader.close()
        return labelList
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

}