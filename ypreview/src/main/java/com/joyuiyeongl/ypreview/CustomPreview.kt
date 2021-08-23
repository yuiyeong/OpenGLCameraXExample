package com.joyuiyeongl.ypreview

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import java.util.*
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
    private val mRenderer: OpenGLRenderer by lazy { OpenGLRenderer() }

    init {
        LayoutInflater.from(context).inflate(R.layout.custom_preview, this, true)
    }

    fun setFrameUpdateListener(executor: Executor, listener: Consumer<Long>) =
        mRenderer.setFrameUpdateListener(executor, listener)

    fun invalidateSurface(surfaceRotationDegrees: Int) =
        mRenderer.invalidateSurface(surfaceRotationDegrees)


    fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get().also { it.unbindAll() }

            val preview = Preview.Builder().build()
            mRenderer.attachInputPreview(preview).addListener(
                { Log.d(TAG, "OpenGLRenderer get the new surface for the Preview") },
                ContextCompat.getMainExecutor(context)
            )
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
        }, ContextCompat.getMainExecutor(context))
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onOwnerDestroy() {
        mRenderer.shutdown()
    }

    companion object {
        private const val TAG = "CustomPreview"

        const val RENDER_SURFACE_TYPE_TEXTUREVIEW = "textureview"
        const val RENDER_SURFACE_TYPE_SURFACEVIEW = "surfaceview"
        const val RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING = "surfaceview_nonblocking"

        private fun chooseViewFinder(
            parent: ViewGroup,
            renderer: OpenGLRenderer,
            renderSurfaceType: String = RENDER_SURFACE_TYPE_TEXTUREVIEW
        ): View {
            return when (renderSurfaceType) {
                RENDER_SURFACE_TYPE_TEXTUREVIEW -> {
                    Log.d(TAG, "Using TextureView render surface.")
                    TextureViewRenderSurface.inflateWith(parent, renderer)
                }
                RENDER_SURFACE_TYPE_SURFACEVIEW -> {
                    Log.d(TAG, "Using SurfaceView render surface.")
                    SurfaceViewRenderSurface.inflateWith(parent, renderer)
                }
                RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING -> {
                    Log.d(TAG, "Using SurfaceView (non-blocking) render surface.")
                    SurfaceViewRenderSurface.inflateNonBlockingWith(parent, renderer)
                }
                else -> throw IllegalArgumentException(
                    String.format(
                        Locale.US,
                        "Unknown render "
                                + "surface type: %s. Supported surface types include: [%s, %s, %s]",
                        renderSurfaceType, RENDER_SURFACE_TYPE_TEXTUREVIEW,
                        RENDER_SURFACE_TYPE_SURFACEVIEW,
                        RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING
                    )
                )
            }
        }
    }
}