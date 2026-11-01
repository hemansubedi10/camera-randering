package com.aiprocamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
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
    PRO_DSLR
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var currentMode = CameraUIMode.NIGHT_SIGHT
    private var latestSceneMetrics: SceneMetrics? = null

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

        setupUI()
        checkPermissionsAndStart()
    }

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
                }
            }
        )

        cameraManager.startCamera {
            // Camera ready
        }
    }

    private fun setupUI() {
        // Tap to focus reticle
        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                binding.focusRing.showAt(event.x, event.y)
            }
            false
        }

        // Shutter Button
        binding.btnShutter.setOnClickListener {
            triggerShutterHaptics()
            executeCapturePipeline()
        }

        // Flash Toggle
        binding.btnFlash.setOnClickListener {
            val state = cameraManager.toggleFlash()
            binding.btnFlash.text = "⚡ $state"
        }

        // Switch Camera (Front/Back)
        binding.btnSwitchCamera.setOnClickListener {
            cameraManager.switchCamera()
        }

        // Pro Exposure Bias Slider
        binding.seekExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val bias = progress - 4 // Range -4 to +4 EV
                cameraManager.setExposureCompensation(bias)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Mode Switching
        binding.modeAuto.setOnClickListener { selectMode(CameraUIMode.AUTO) }
        binding.modeHdr.setOnClickListener { selectMode(CameraUIMode.HDR) }
        binding.modeNight.setOnClickListener { selectMode(CameraUIMode.NIGHT_SIGHT) }
        binding.modePro.setOnClickListener { selectMode(CameraUIMode.PRO_DSLR) }

        selectMode(CameraUIMode.NIGHT_SIGHT)
    }

    private fun selectMode(mode: CameraUIMode) {
        currentMode = mode
        val modes = listOf(
            binding.modeAuto to CameraUIMode.AUTO,
            binding.modeHdr to CameraUIMode.HDR,
            binding.modeNight to CameraUIMode.NIGHT_SIGHT,
            binding.modePro to CameraUIMode.PRO_DSLR
        )

        modes.forEach { (view, m) ->
            if (m == mode) {
                view.setTextColor(getColor(R.color.gold_pro))
            } else {
                view.setTextColor(getColor(R.color.gray_text))
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
                            // 1. Bracketed capture (-2 EV, 0 EV, +2 EV)
                            updateProcessStatus("Capturing 3-Frame Bracket...")
                            val bracketFrames = cameraManager.captureBracketedBurst(listOf(-2, 0, 2))

                            // 2. Mertens multi-exposure fusion
                            updateProcessStatus("Mertens Exposure Fusion...")
                            val fused = ComputationalEngine.fuseBracketed(bracketFrames)

                            // 3. Bilateral edge-preserving denoising
                            updateProcessStatus("Edge-Preserving Denoising...")
                            val denoised = ComputationalEngine.bilateralDenoise(fused)

                            // 4. Tone reproduction + Nepal Twilight / Landscape grading
                            updateProcessStatus("Filmic Tone Reproduction...")
                            val graded = ComputationalEngine.toneMapAndGrade(
                                denoised,
                                latestSceneMetrics?.recommendedStyle ?: ColorStyle.NEPAL_TWILIGHT
                            )

                            // 5. Unsharp mask detail sharpening
                            updateProcessStatus("Micro-Contrast Sharpening...")
                            ComputationalEngine.unsharpMask(graded)
                        }

                        CameraUIMode.AUTO, CameraUIMode.PRO_DSLR -> {
                            // Single frame pro computational pipeline
                            updateProcessStatus("Capturing Frame...")
                            val rawFrame = cameraManager.captureSingleFrame()

                            updateProcessStatus("Filmic Tone Reproduction...")
                            val graded = ComputationalEngine.toneMapAndGrade(
                                rawFrame,
                                ColorStyle.LEICA_NATURAL
                            )

                            updateProcessStatus("Detail Sharpening...")
                            ComputationalEngine.unsharpMask(graded)
                        }
                    }
                }

                // Save to MediaStore
                val path = cameraManager.saveToGallery(processedBitmap)

                // Update Gallery thumbnail on UI
                binding.imgGalleryPreview.setImageBitmap(
                    Bitmap.createScaledBitmap(processedBitmap, 96, 96, false)
                )

                Toast.makeText(this@MainActivity, "Photo saved: $path", Toast.LENGTH_SHORT).show()

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
            vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(45)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
    }
}
