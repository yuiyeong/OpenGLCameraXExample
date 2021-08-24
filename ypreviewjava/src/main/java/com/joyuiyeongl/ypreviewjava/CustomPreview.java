package com.joyuiyeongl.ypreviewjava;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewStub;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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

public class CustomPreview extends FrameLayout implements LifecycleObserver {
    private static final String TAG = "CustomPreview";

    private LifecycleOwner lifecycleOwner;
    private OpenGLRenderer renderer;

    private ViewStub viewFinderStub;

    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
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
    };


    public CustomPreview(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CustomPreview(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CustomPreview(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public CustomPreview(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.custom_preview, this, true);
        viewFinderStub = findViewById(R.id.viewFinderStub);
        renderer = new OpenGLRenderer();
        displayManager = (DisplayManager) context.getSystemService(AppCompatActivity.DISPLAY_SERVICE);

    }


    public void bind(LifecycleOwner lifecycleOwner) {
        bind(lifecycleOwner, false);
    }

    public void bind(LifecycleOwner lifecycleOwner, boolean isTextureView) {
        this.lifecycleOwner = lifecycleOwner;
        this.lifecycleOwner.getLifecycle().addObserver(this);

        if (isTextureView) {
            TextureViewRenderSurface.inflateWith(viewFinderStub, renderer);
        } else if (Build.MODEL.contains("Cuttlefish")) {
            SurfaceViewRenderSurface.inflateWith(viewFinderStub, renderer);
        } else {
            SurfaceViewRenderSurface.inflateNonBlockingWith(viewFinderStub, renderer);
        }
    }

    public void setFrameUpdateListener(Executor executor, Consumer<Long> consumer) {
        renderer.setFrameUpdateListener(executor, consumer);
    }

    public void startCamera() {
        validateState();

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(getContext());
            providerFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = providerFuture.get();
                    Preview preview = new Preview.Builder().build();
                    renderer.attachInputPreview(preview).addListener(
                            () -> Log.d(TAG, "OpenGLRenderer get the new surface for the Preview"),
                            ContextCompat.getMainExecutor(getContext())
                    );

                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, ContextCompat.getMainExecutor(getContext()));
        } else {
            Log.w(TAG, "CAMERA permission is NOT granted");
        }
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void onOwnerCreate() {
        displayManager.registerDisplayListener(displayListener, getHandler());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void onOwnerDestroy() {
        displayManager.unregisterDisplayListener(displayListener);

        renderer.shutdown();
    }


    private void validateState() {
        if (lifecycleOwner == null) {
            throw new IllegalStateException(TAG + " If you want to start camera, bind this view to LifecycleOwner FIRST");
        }
    }
}
