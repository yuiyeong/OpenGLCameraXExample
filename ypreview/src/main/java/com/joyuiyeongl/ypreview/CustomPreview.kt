package com.joyuiyeongl.ypreview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewStub
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import java.util.concurrent.Executor

class CustomPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(
    context,
    attrs,
    defStyle
), LifecycleObserver {
    private var lifecycleOwner: LifecycleOwner? = null
    private val renderer: OpenGLRenderer by lazy { OpenGLRenderer() }

    private var viewFinderStub: ViewStub

    private val displayManager: DisplayManager by lazy { context.getSystemService(AppCompatActivity.DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (display != null && display.displayId == displayId) {
                renderer.invalidateSurface(Surfaces.toSurfaceRotationDegrees(display.rotation))
            }
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.custom_preview, this, true)
        viewFinderStub = findViewById(R.id.viewFinderStub)
    }


    @JvmOverloads
    fun bind(lifecycleOwner: LifecycleOwner, isTextureView: Boolean = false) {
        lifecycleOwner.lifecycle.addObserver(this)
        this.lifecycleOwner = lifecycleOwner

        when {
            isTextureView -> TextureViewRenderSurface.inflateWith(viewFinderStub, renderer)
            Build.MODEL.contains("Cuttlefish") -> SurfaceViewRenderSurface.inflateWith(viewFinderStub, renderer)
            else -> SurfaceViewRenderSurface.inflateNonBlockingWith(viewFinderStub, renderer)
        }
    }

    fun setFrameUpdateListener(executor: Executor, listener: Consumer<Long>) =
        renderer.setFrameUpdateListener(executor, listener)


    fun startCamera() {
        validateState()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val lifecycleOwner = lifecycleOwner ?: return
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get().also { it.unbindAll() }

                val preview = Preview.Builder().build()
                renderer.attachInputPreview(preview).addListener(
                    { Log.d(TAG, "OpenGLRenderer get the new surface for the Preview") },
                    ContextCompat.getMainExecutor(context)
                )
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            }, ContextCompat.getMainExecutor(context))
        } else {
            Log.w(TAG,"CAMERA Permission is NOT granted")
        }
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onOwnerCreate() {
        displayManager.registerDisplayListener(displayListener, handler)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onOwnerDestroy() {
        displayManager.unregisterDisplayListener(displayListener)

        renderer.shutdown()
    }

    private fun validateState(){
        if (lifecycleOwner == null) {
            throw IllegalStateException("If you want to start camera, bind this view to LifecycleOwner FIRST")
        }
    }


    companion object {
        private const val TAG = "CustomPreview"
    }
}