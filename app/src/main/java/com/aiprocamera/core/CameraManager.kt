package com.aiprocamera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCancellableCoroutine

class CameraManager(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val onMetricsUpdated: (SceneMetrics) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    fun startCamera(onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
            onReady()
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, AiSceneAnalyzer(onMetricsUpdated))
            }

        provider.unbindAll()
        val camera = provider.bindToLifecycle(
            activity,
            cameraSelector,
            preview,
            imageCapture,
            imageAnalysis
        )

        cameraControl = camera.cameraControl
        cameraInfo = camera.cameraInfo
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    fun toggleFlash(): String {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        return when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            ImageCapture.FLASH_MODE_ON -> "ON"
            else -> "OFF"
        }
    }

    fun setExposureCompensation(biasIndex: Int) {
        cameraControl?.setExposureCompensationIndex(biasIndex)
    }

    /**
     * Executes multi-exposure bracketed burst:
     * Frame 1: -2 EV (highlights/peaks/sky protection)
     * Frame 2: 0 EV (balanced midtones)
     * Frame 3: +2 EV (shadow/valley recovery)
     */
    suspend fun captureBracketedBurst(evSteps: List<Int> = listOf(-2, 0, 2)): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        for (ev in evSteps) {
            setExposureCompensation(ev)
            // Small pause for sensor AE latch
            withContext(Dispatchers.IO) { Thread.sleep(60) }
            val frame = captureSingleFrame()
            bitmaps.add(frame)
        }
        // Reset EV back to 0
        setExposureCompensation(0)
        return bitmaps
    }

    suspend fun captureSingleFrame(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.cancel()
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    cont.resume(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.cancel(exception)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Rotate if needed according to image rotation degrees
        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        return decoded
    }

    suspend fun saveToGallery(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val filename = "AI_PRO_$timeStamp.jpg"

        var outputStream: OutputStream? = null
        var resultUriString = ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "AIProCamera")
            }
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                outputStream = activity.contentResolver.openOutputStream(uri)
                resultUriString = uri.toString()
            }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + File.separator + "AIProCamera"
            val fileDir = File(imagesDir)
            if (!fileDir.exists()) fileDir.mkdirs()
            val file = File(fileDir, filename)
            outputStream = FileOutputStream(file)
            resultUriString = file.absolutePath
        }

        outputStream?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it)
        }

        resultUriString
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
