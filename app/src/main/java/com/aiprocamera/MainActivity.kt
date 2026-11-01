package com.aiprocamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aiprocamera.core.CameraManager
import com.aiprocamera.core.ColorStyle
import com.aiprocamera.core.ComputationalEngine
import com.aiprocamera.core.SceneMetrics
import com.aiprocamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CameraUIMode {
    AUTO,
    HDR,
    NIGHT_SIGHT,
    PORTRAIT,
    MACRO,
    PRO_DSLR
}

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var currentMode = CameraUIMode.NIGHT_SIGHT
    private var latestSceneMetrics: SceneMetrics? = null
    private var lastSavedUri: String? = null

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomRatio = 1.0f

    // Sensor manager for spirit level
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null

    // Self-timer seconds (0 = off, 3 = 3s, 10 = 10s)
    private var timerSeconds = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            initCamera()
        } else {
            Toast.makeText(this, "Camera permission required for AI Pro Camera", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initSensors()
        setupUI()
        checkPermissionsAndStart()
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            // Roll in degrees (pitch and roll)
            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            binding.cameraOverlay.pitchAngle = pitch
            binding.cameraOverlay.rollAngle = roll
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val roll = Math.toDegrees(kotlin.math.atan2(-ax.toDouble(), ay.toDouble())).toFloat()
            binding.cameraOverlay.rollAngle = roll
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkPermissionsAndStart() {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            initCamera()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun initCamera() {
        cameraManager = CameraManager(
            activity = this,
            previewView = binding.previewView,
            onMetricsUpdated = { metrics ->
                runOnUiThread {
                    latestSceneMetrics = metrics
                    binding.txtAiScene.text = "AI: ${metrics.luxEstimate.toInt()} Lux • ${metrics.sceneType}"
                    binding.cameraOverlay.updateHistogram(metrics.histogramBins)
                }
            }
        )

        cameraManager.startCamera {
            runOnUiThread {
                cameraManager.getZoomState()?.observe(this) { zoomState ->
                    if (zoomState != null) {
                        currentZoomRatio = zoomState.zoomRatio
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        // Pinch-to-zoom detector
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                currentZoomRatio *= factor
                cameraManager.setZoomRatio(currentZoomRatio)
                updateZoomPillsHighlight(currentZoomRatio)
                return true
            }
        })

        // Tap to focus reticle + meter
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
                binding.focusRing.showAt(event.x, event.y)
                if (::cameraManager.isInitialized) {
                    cameraManager.triggerFocusAndMetering(event.x, event.y)
                }
            }
            true
        }

        // Shutter Button
        binding.btnShutter.setOnClickListener {
            handleShutterClick()
        }

        // Self-Timer Toggle (0s -> 3s -> 10s -> 0s)
        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            if (timerSeconds > 0) {
                binding.btnTimer.setTextColor(ContextCompat.getColor(this, R.color.gold_pro))
                binding.btnTimer.text = "⏱ ${timerSeconds}s"
            } else {
                binding.btnTimer.setTextColor(ContextCompat.getColor(this, R.color.gray_text))
                binding.btnTimer.text = "⏱ OFF"
            }
        }

        // Grid / Leveler Toggle
        binding.btnGridToggle.setOnClickListener {
            val isGridOn = binding.cameraOverlay.showGrid
            binding.cameraOverlay.showGrid = !isGridOn
            binding.cameraOverlay.showLeveler = !isGridOn
            if (!isGridOn) {
                binding.btnGridToggle.setTextColor(ContextCompat.getColor(this, R.color.gold_pro))
            } else {
                binding.btnGridToggle.setTextColor(ContextCompat.getColor(this, R.color.gray_text))
            }
        }

        // Flash Toggle
        binding.btnFlash.setOnClickListener {
            val state = cameraManager.toggleFlash()
            binding.btnFlash.text = "FLASH $state"
        }

        // Torch Light Toggle
        binding.btnTorch.setOnClickListener {
            val isOn = cameraManager.toggleTorch()
            if (isOn) {
                binding.btnTorch.setTextColor(ContextCompat.getColor(this, R.color.gold_pro))
                binding.btnTorch.text = "TORCH ON"
            } else {
                binding.btnTorch.setTextColor(ContextCompat.getColor(this, R.color.gray_text))
                binding.btnTorch.text = "TORCH"
            }
        }

        // Switch Camera (Front/Back)
        binding.btnSwitchCamera.setOnClickListener {
            cameraManager.switchCamera()
        }

        // Zoom Quick Switch Buttons (0.6x, 1x, 2x, 5x)
        binding.btnZoom06.setOnClickListener { applyDirectZoom(0.6f) }
        binding.btnZoom1.setOnClickListener { applyDirectZoom(1.0f) }
        binding.btnZoom2.setOnClickListener { applyDirectZoom(2.0f) }
        binding.btnZoom5.setOnClickListener { applyDirectZoom(5.0f) }

        // Pro Exposure Bias Slider
        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val bias = progress - 4 // Range -4 to +4 EV
                cameraManager.setExposureCompensation(bias)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Gallery Preview click
        binding.imgGalleryPreview.setOnClickListener {
            lastSavedUri?.let { uriStr ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(uriStr), "image/jpeg")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Opening default Gallery...", Toast.LENGTH_SHORT).show()
                    val galleryIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_GALLERY)
                    }
                    startActivity(galleryIntent)
                }
            }
        }

        // Mode Switching
        binding.modeAuto.setOnClickListener { selectMode(CameraUIMode.AUTO) }
        binding.modeHdr.setOnClickListener { selectMode(CameraUIMode.HDR) }
        binding.modeNight.setOnClickListener { selectMode(CameraUIMode.NIGHT_SIGHT) }
        binding.modePortrait.setOnClickListener { selectMode(CameraUIMode.PORTRAIT) }
        binding.modeMacro.setOnClickListener { selectMode(CameraUIMode.MACRO) }
        binding.modePro.setOnClickListener { selectMode(CameraUIMode.PRO_DSLR) }

        selectMode(CameraUIMode.NIGHT_SIGHT)
    }

    private fun handleShutterClick() {
        if (!binding.btnShutter.isEnabled) return
        triggerShutterHaptics()

        if (timerSeconds > 0) {
            binding.btnShutter.isEnabled = false
            binding.txtCountdown.visibility = View.VISIBLE
            object : CountDownTimer((timerSeconds * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = (millisUntilFinished / 1000) + 1
                    binding.txtCountdown.text = "$sec"
                    triggerShutterHaptics()
                }

                override fun onFinish() {
                    binding.txtCountdown.visibility = View.GONE
                    executeCapturePipeline()
                }
            }.start()
        } else {
            executeCapturePipeline()
        }
    }

    private fun applyDirectZoom(ratio: Float) {
        currentZoomRatio = ratio
        if (::cameraManager.isInitialized) {
            cameraManager.setZoomRatio(ratio)
        }
        updateZoomPillsHighlight(ratio)
    }

    private fun updateZoomPillsHighlight(ratio: Float) {
        val pills = listOf(
            binding.btnZoom06 to 0.6f,
            binding.btnZoom1 to 1.0f,
            binding.btnZoom2 to 2.0f,
            binding.btnZoom5 to 5.0f
        )
        pills.forEach { (view, r) ->
            if (kotlin.math.abs(ratio - r) < 0.35f) {
                view.setTextColor(ContextCompat.getColor(this, R.color.gold_pro))
            } else {
                view.setTextColor(ContextCompat.getColor(this, R.color.gray_text))
            }
        }
    }

    private fun selectMode(mode: CameraUIMode) {
        currentMode = mode
        val modes = listOf(
            binding.modeAuto to CameraUIMode.AUTO,
            binding.modeHdr to CameraUIMode.HDR,
            binding.modeNight to CameraUIMode.NIGHT_SIGHT,
            binding.modePortrait to CameraUIMode.PORTRAIT,
            binding.modeMacro to CameraUIMode.MACRO,
            binding.modePro to CameraUIMode.PRO_DSLR
        )

        modes.forEach { (view, m) ->
            if (m == mode) {
                view.setTextColor(ContextCompat.getColor(this, R.color.gold_pro))
            } else {
                view.setTextColor(ContextCompat.getColor(this, R.color.gray_text))
            }
        }
    }

    private fun executeCapturePipeline() {
        binding.btnShutter.isEnabled = false
        binding.processingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val processedBitmap: Bitmap = withContext(Dispatchers.Default) {
                    when (currentMode) {
                        CameraUIMode.NIGHT_SIGHT, CameraUIMode.HDR -> {
                            updateProcessStatus("Capturing 3-Frame Bracket...")
                            val bracketFrames = cameraManager.captureBracketedBurst(listOf(-2, 0, 2))

                            updateProcessStatus("Mertens Exposure Fusion...")
                            val fused = ComputationalEngine.fuseBracketed(bracketFrames)

                            updateProcessStatus("Edge-Preserving Denoising...")
                            val denoised = ComputationalEngine.bilateralDenoise(fused)

                            updateProcessStatus("Filmic Tone Reproduction...")
                            val graded = ComputationalEngine.toneMapAndGrade(
                                denoised,
                                latestSceneMetrics?.recommendedStyle ?: ColorStyle.NEPAL_TWILIGHT
                            )

                            updateProcessStatus("Micro-Contrast Sharpening...")
                            ComputationalEngine.unsharpMask(graded)
                        }

                        CameraUIMode.PORTRAIT -> {
                            updateProcessStatus("Capturing Portrait Frame...")
                            val rawFrame = cameraManager.captureSingleFrame()

                            updateProcessStatus("Leica Authentic Tone Grading...")
                            val graded = ComputationalEngine.toneMapAndGrade(
                                rawFrame,
                                ColorStyle.LEICA_NATURAL
                            )

                            updateProcessStatus("Optical Bokeh Fall-Off...")
                            val bokeh = ComputationalEngine.applyPortraitBokeh(graded)

                            updateProcessStatus("Sharpening Subject Eyes & Facial Detail...")
                            ComputationalEngine.unsharpMask(bokeh, amount = 0.5f)
                        }

                        CameraUIMode.MACRO -> {
                            updateProcessStatus("Capturing Macro Detail...")
                            val rawFrame = cameraManager.captureSingleFrame()

                            updateProcessStatus("Ultra Texture & Edge Clarification...")
                            ComputationalEngine.applyMacroEnhancement(rawFrame)
                        }

                        CameraUIMode.AUTO, CameraUIMode.PRO_DSLR -> {
                            updateProcessStatus("Capturing Frame...")
                            val rawFrame = cameraManager.captureSingleFrame()

                            updateProcessStatus("Filmic Tone Reproduction...")
                            val graded = ComputationalEngine.toneMapAndGrade(
                                rawFrame,
                                latestSceneMetrics?.recommendedStyle ?: ColorStyle.LEICA_NATURAL
                            )

                            updateProcessStatus("Detail Sharpening...")
                            ComputationalEngine.unsharpMask(graded)
                        }
                    }
                }

                val path = cameraManager.saveToGallery(processedBitmap)
                lastSavedUri = path

                binding.imgGalleryPreview.setImageBitmap(
                    Bitmap.createScaledBitmap(processedBitmap, 96, 96, false)
                )

                Toast.makeText(this@MainActivity, "Photo saved!", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.processingOverlay.visibility = View.GONE
                binding.btnShutter.isEnabled = true
            }
        }
    }

    private fun updateProcessStatus(status: String) {
        runOnUiThread {
            binding.txtProcessStatus.text = status
        }
    }

    private fun triggerShutterHaptics() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(45L)
        }
    }

    // Hardware volume keys integration (Physical Shutter & Zoom)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleShutterClick()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                currentZoomRatio = (currentZoomRatio + 0.5f).coerceAtMost(8.0f)
                if (::cameraManager.isInitialized) {
                    cameraManager.setZoomRatio(currentZoomRatio)
                }
                updateZoomPillsHighlight(currentZoomRatio)
                true
            }
            KeyEvent.KEYCODE_CAMERA -> {
                handleShutterClick()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
    }
}
