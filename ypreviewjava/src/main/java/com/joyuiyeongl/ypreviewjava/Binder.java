package com.joyuiyeongl.ypreviewjava;

import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.window.WindowManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

/**
 * Class that helps CustomPreview bind into lifecycle owner
 */
public class Binder implements LifecycleObserver {
    private static final String TAG = "Binder";

    private static final double RATIO_4_3_VALUE = 4.0 / 3.0;
    private static final double RATIO_16_9_VALUE = 16.0 / 9.0;

    @Nullable
    private ProcessCameraProvider cameraProvider = null;
    @Nullable
    private Camera camera = null;

    @NonNull
    private final PreviewView previewView;
    @NonNull
    private final DisplayManager displayManager;
    @NonNull
    private final WindowManager windowManager;


    @NonNull
    private final LifecycleOwner lifecycleOwner;

    private int lensFacing = CameraSelector.LENS_FACING_FRONT;


    public Binder(@NonNull PreviewView view, @NonNull LifecycleOwner owner) {
        previewView = view;
        lifecycleOwner = owner;
        displayManager = (DisplayManager) previewView.getContext().getSystemService(AppCompatActivity.DISPLAY_SERVICE);
        windowManager = new WindowManager(previewView.getContext());

        lifecycleOwner.getLifecycle().addObserver(this);
        previewView.inflate(false);
    }

    public Binder(@NonNull PreviewView view, @NonNull LifecycleOwner owner, boolean isTextureView) {
        previewView = view;
        lifecycleOwner = owner;
        displayManager = (DisplayManager) previewView.getContext().getSystemService(AppCompatActivity.DISPLAY_SERVICE);
        windowManager = new WindowManager(previewView.getContext());

        previewView.inflate(isTextureView);
        lifecycleOwner.getLifecycle().addObserver(this);
    }

    public void setUpCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.getContext());
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                cameraProvider = null;
            }

            setUpLensFacing();

            rebind();
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    public void rebind() {
        if (!isCameraXInitialized()) throw new IllegalStateException("Camera initialization failed.");

        Rect metrics = windowManager.getCurrentWindowMetrics().getBounds();
        Log.d(TAG, "Screen metrics: " + metrics.width() + " x " + metrics.height());
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        // Preview
        Preview preview = new Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(aspectRatio(metrics.width(), metrics.height()))
                // Set initial target rotation
                .setTargetRotation(previewView.getDisplayRotation())
                .build();

        cameraProvider.unbindAll();

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview);
            previewView.setPreviewUseCase(preview);
        } catch (Exception exc) {
            Log.e(TAG, "Use case binding failed", exc);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void onOwnerCreate() {
        Log.e(TAG, "onOwnerCreate");
        displayManager.registerDisplayListener(previewView, previewView.getHandler());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void onOwnerDestroy() {
        Log.e(TAG, "onOwnerDestroy");
        displayManager.unregisterDisplayListener(previewView);
        previewView.shutdown();

        lifecycleOwner.getLifecycle().removeObserver(this);
    }


    private boolean isCameraXInitialized() {
        return cameraProvider != null;
    }

    /**
     * Default lens is front lens.
     * But there is no available the front lens,
     * then uses the back lens
     * <p>
     * IllegalStateException is thrown if all lens is not available.
     */
    private void setUpLensFacing() {
        if (hasFrontCamera()) {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        } else if (hasBackCamera()) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            throw new IllegalStateException("Back and front camera are unavailable");
        }
    }

    /**
     * Returns true if the device has an available back camera. False otherwise
     */
    private boolean hasBackCamera() {
        try {
            return isCameraXInitialized() && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
        } catch (CameraInfoUnavailableException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Returns true if the device has an available front camera. False otherwise
     */
    private boolean hasFrontCamera() {
        try {
            return isCameraXInitialized() && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
        } catch (CameraInfoUnavailableException e) {
            e.printStackTrace();
        }

        return false;
    }


    /**
     * [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     * [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     * <p>
     * Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     * of preview ratio to one of the provided values.
     *
     * @param width  - preview width
     * @param height - preview height
     * @return suitable aspect ratio
     */
    private int aspectRatio(int width, int height) {
        double previewRatio = ((double) Math.max(width, height)) / ((double) Math.min(width, height));
        if (Math.abs(previewRatio - RATIO_4_3_VALUE) <= Math.abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3;
        } else {
            return AspectRatio.RATIO_16_9;
        }
    }
}