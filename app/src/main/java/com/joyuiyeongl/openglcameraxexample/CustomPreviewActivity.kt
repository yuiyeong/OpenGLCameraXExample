package com.joyuiyeongl.openglcameraxexample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.joyuiyeongl.openglcameraxexample.databinding.ActivityCustomPreviewBinding
import com.joyuiyeongl.ypreviewjava.Binder

class CustomPreviewActivity : AppCompatActivity() {
    private val binding: ActivityCustomPreviewBinding by lazy { ActivityCustomPreviewBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            mRequestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }


    // **************************** Permission handling code start *******************************//
    private val mRequestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        for (permission: String in REQUIRED_PERMISSIONS) {
            if (result[permission] == false) {
                Toast.makeText(this@CustomPreviewActivity, "Permissions not granted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // All permissions granted.
        startCamera()
    }

    private fun startCamera() {
        Binder(binding.preview, this).setUpCamera()
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