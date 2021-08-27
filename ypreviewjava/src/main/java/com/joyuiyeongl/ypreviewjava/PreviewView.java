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
}
