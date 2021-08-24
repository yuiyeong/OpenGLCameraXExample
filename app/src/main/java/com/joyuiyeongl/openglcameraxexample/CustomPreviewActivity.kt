package com.joyuiyeongl.openglcameraxexample

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.joyuiyeongl.openglcameraxexample.databinding.ActivityCustomPreviewBinding

class CustomPreviewActivity : AppCompatActivity() {
    private val binding: ActivityCustomPreviewBinding by lazy {
        ActivityCustomPreviewBinding.inflate(
            layoutInflater
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.customPreview.bind(this)

        if (allPermissionsGranted()) {
            binding.customPreview.startCamera()
        } else {
            mRequestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }


    // **************************** Permission handling code start *******************************//
    private val mRequestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        for (permission: String in REQUIRED_PERMISSIONS) {
            if (result[permission] == true) {
                Toast.makeText(
                    this@CustomPreviewActivity, "Permissions not granted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        // All permissions granted.
        binding.customPreview.startCamera()
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    } // **************************** Permission handling code end *********************************//

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}