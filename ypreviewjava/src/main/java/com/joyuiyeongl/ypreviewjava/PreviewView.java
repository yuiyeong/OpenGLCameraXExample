package com.joyuiyeongl.ypreviewjava;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.ViewStub;
import android.widget.FrameLayout;

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
import androidx.core.util.Consumer;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class PreviewView extends FrameLayout implements DisplayManager.DisplayListener {
    private static final String TAG = "CustomPreview";

    private OpenGLRenderer renderer;
    private ViewStub viewFinderStub;


    public PreviewView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.custom_preview, this, true);
        viewFinderStub = findViewById(R.id.viewFinderStub);
        renderer = new OpenGLRenderer();
    }

    public void setFrameUpdateListener(Executor executor, Consumer<Long> consumer) {
        renderer.setFrameUpdateListener(executor, consumer);
    }

    public int getDisplayRotation() {
        Display display = getDisplay();
        if (display != null)
            return getDisplay().getRotation();
        else
            return 0;
    }

    void inflate(boolean isTextureView) {
        if (isTextureView) {
            TextureViewRenderSurface.inflateWith(viewFinderStub, renderer);
        } else if (Build.MODEL.contains("Cuttlefish")) {
            SurfaceViewRenderSurface.inflateWith(viewFinderStub, renderer);
        } else {
            SurfaceViewRenderSurface.inflateNonBlockingWith(viewFinderStub, renderer);
        }
    }

    void setPreviewUseCase(Preview preview) {
        renderer.attachInputPreview(preview);
    }

    void shutdown() {
        renderer.shutdown();
    }


    // *********************** Start Overriding Display.DisplayListener ************************
    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (getDisplay() != null && getDisplay().getDisplayId() == displayId) {
            renderer.invalidateSurface(Surfaces.toSurfaceRotationDegrees(getDisplay().getRotation()));
        }
    }
    // *********************** End Overriding Display.DisplayListener ************************


    /**
     * Class that helps CustomPreview bind into lifecycle owner
     */
    public static class Binder implements LifecycleObserver {
        private static final String TAG = "CustomPreview::Binder";

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
        private final LifecycleOwner lifecycleOwner;

        private int lensFacing = CameraSelector.LENS_FACING_FRONT;


        public Binder(@NonNull PreviewView view, @NonNull LifecycleOwner owner) {
            this.previewView = view;
            this.lifecycleOwner = owner;
            displayManager = (DisplayManager) previewView.getContext().getSystemService(AppCompatActivity.DISPLAY_SERVICE);

            this.lifecycleOwner.getLifecycle().addObserver(this);
            previewView.inflate(false);
        }

        public Binder(@NonNull PreviewView view, @NonNull LifecycleOwner owner, boolean isTextureView) {
            this.previewView = view;
            this.lifecycleOwner = owner;
            displayManager = (DisplayManager) previewView.getContext().getSystemService(AppCompatActivity.DISPLAY_SERVICE);

            previewView.inflate(isTextureView);
            this.lifecycleOwner.getLifecycle().addObserver(this);
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
            
            CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
            // Preview
            Preview preview = new Preview.Builder()
                    // We request aspect ratio but no resolution
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
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
            displayManager.registerDisplayListener(previewView, previewView.getHandler());
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        public void onOwnerDestroy() {
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
                lensFacing = CameraSelector.LENS_FACING_FRONT;
            } else if (hasBackCamera()) {
                lensFacing = CameraSelector.LENS_FACING_BACK;
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
}
