package com.joyuiyeongl.appasjava;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.joyuiyeongl.ypreview.CustomPreview;

import java.util.Map;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private CustomPreview customPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        customPreview = findViewById(R.id.customPreview);

        if (allPermissionsGranted()) {
            customPreview.bind((LifecycleOwner) this);
            customPreview.startCamera();
        } else {
            mRequestPermissions.launch(REQUIRED_PERMISSIONS);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private final ActivityResultLauncher<String[]> mRequestPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            new ActivityResultCallback<Map<String, Boolean>>() {
                @Override
                public void onActivityResult(Map<String, Boolean> result) {
                    for (String permission : REQUIRED_PERMISSIONS) {
                        if (!Objects.requireNonNull(result.get(permission))) {
                            Toast.makeText(MainActivity.this, "Permissions not granted", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                    }

                    customPreview.bind((LifecycleOwner) MainActivity.this, false);
                    customPreview.startCamera();
                }
            }
    );
}