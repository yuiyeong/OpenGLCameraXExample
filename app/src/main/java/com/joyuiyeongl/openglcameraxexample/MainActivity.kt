package com.joyuiyeongl.openglcameraxexample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.joyuiyeongl.openglcameraxexample.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() } // It's for analysis
    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        binding.takingButton.setOnClickListener { takePhoto() }

        if (arePermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_PERMISSION_CODE
            )
        }
    }

    private fun arePermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            baseContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == 0 }) {
            startCamera()
        } else {
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get().also { it.unbindAll() }

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.preview.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exception: ImageCaptureException) {
                    super.onError(exception)
                    exception.printStackTrace()
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    super.onCaptureSuccess(image)

                    Log.e(
                        "test",
                        "${image.format} ${image.imageInfo.rotationDegrees} ${image.imageInfo.timestamp}"
                    )
                    image.close()

                }
            })
    }

    override fun onDestroy() {
        super.onDestroy()

        cameraExecutor.shutdown()
    }


    companion object {
        private const val REQUEST_PERMISSION_CODE = 41928
    }
}