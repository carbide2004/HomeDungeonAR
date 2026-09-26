package com.homedungeon.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.homedungeon.ar.databinding.ActivityMainBinding
import com.homedungeon.core.TerminalStatus

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val terminalStatus = TerminalStatus(calibrated = false, currentRoom = "Real-World Base")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            binding.permissionLayout.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionLayout.visibility = View.VISIBLE
            Toast.makeText(this, "需授予相机权限以观测异常", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantPermission.setOnClickListener {
            requestCameraPermission()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
                binding.tvStatus.text = getString(R.string.status_ready)
            } catch (exc: Exception) {
                binding.tvStatus.text = "相机启动失败: ${exc.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
