package com.joyuiyeong.previewview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.util.Rational;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ViewPort;
import androidx.core.content.ContextCompat;

public class PreviewView extends FrameLayout {
    private static final String TAG = "PreviewView";

    private PreviewViewImplementation mImplementation;
    private final PreviewTransformation mPreviewTransform = new PreviewTransformation();

    private final OnLayoutChangeListener mOnLayoutChangeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        boolean isSizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop;
        if (isSizeChanged) {

            redrawPreview();
            // TODO attachToControllerIfReady(true);
        }
    };

    private final Preview.SurfaceProvider mSurfaceProvider = new Preview.SurfaceProvider() {

        @SuppressLint("UnsafeOptInUsageError")
        @Override
        public void onSurfaceRequested(@NonNull SurfaceRequest surfaceRequest) {
            // CameraInternal camera = surfaceRequest.getCamera();
            surfaceRequest.setTransformationInfoListener(ContextCompat.getMainExecutor(getContext()), transformationInfo -> {
                boolean isFrontCamera = true;
                mPreviewTransform.setTransformationInfo(transformationInfo, surfaceRequest.getResolution(), isFrontCamera);
                redrawPreview();
            });
            mImplementation = new TextureViewImplementation(PreviewView.this, mPreviewTransform);
            mImplementation.onSurfaceRequested(surfaceRequest, () -> {
                // Do something
            });
        }
    };


    public PreviewView(@NonNull Context context) {
        this(context, null);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PreviewView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        addOnLayoutChangeListener(mOnLayoutChangeListener);
        if (mImplementation != null) {
            mImplementation.onAttachedToWindow();
        }
        // TODO attachToControllerIfReady(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeOnLayoutChangeListener(mOnLayoutChangeListener);
        if (mImplementation != null) {
            mImplementation.onDetachedFromWindow();
        }
    }

    public Preview.SurfaceProvider getSurfaceProvider() {
        return mSurfaceProvider;
    }

    @Nullable
    public Bitmap getBitmap() {
        return mImplementation != null ? mImplementation.getBitmap() : null;
    }

    @Nullable
    public ViewPort getViewPort() {
        return getDisplay() == null ? null : getViewPort(getDisplay().getRotation());
    }

    @SuppressLint({"UnsafeOptInUsageError", "WrongConstant"})
    public ViewPort getViewPort(int targetRotation) {
        if (getWidth() == 0 || getHeight() == 0) {
            return null;
        }
        return new ViewPort.Builder(new Rational(getWidth(), getHeight()), targetRotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .setLayoutDirection(getLayoutDirection())
                .build();
    }


    private void redrawPreview() {
        if (mImplementation != null) {
            mImplementation.redrawPreview();
        }
    }
}
