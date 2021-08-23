package com.joyuiyeongl.openglcameraxexample

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.joyuiyeongl.ypreview.*
import java.util.*


/** Activity which runs the camera preview with opengl processing  */
class OpenGLActivity : AppCompatActivity() {
    private val mRenderer: OpenGLRenderer by lazy { OpenGLRenderer() }
    private var mDisplayListener: DisplayListener? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.opengl_activity)

        val viewFinderStub = findViewById<ViewStub>(R.id.viewFinderStub)
        val viewFinder = chooseViewFinder(
            intent.extras, viewFinderStub,
            mRenderer
        )

        // Add a frame update listener to display FPS
        val fpsRecorder = FpsRecorder(FPS_NUM_SAMPLES)
        val fpsCounterView = findViewById<TextView>(R.id.fps_counter)
        mRenderer.setFrameUpdateListener(ContextCompat.getMainExecutor(this)) { timestamp ->
            val fps: Double = fpsRecorder.recordTimestamp(timestamp)
            fpsCounterView.text = getString(
                R.string.fps_counter_template,
                if ((java.lang.Double.isNaN(fps) || java.lang.Double.isInfinite(fps))) "---" else String.format(
                    Locale.US,
                    "%.0f", fps
                )
            )
        }

        // A display listener is needed when the phone rotates 180 degrees without stopping at a
        // 90 degree increment. In these cases, onCreate() isn't triggered, so we need to ensure
        // the output surface uses the correct orientation.
        mDisplayListener = object : DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val viewFinderDisplay = viewFinder.display
                if (viewFinderDisplay != null
                    && viewFinderDisplay.displayId == displayId
                ) {
                    mRenderer.invalidateSurface(
                        Surfaces.toSurfaceRotationDegrees(
                            viewFinderDisplay.rotation
                        )
                    )
                }
            }
        }
        val dpyMgr = Objects.requireNonNull(getSystemService(DISPLAY_SERVICE) as DisplayManager)
        dpyMgr.registerDisplayListener(mDisplayListener, Handler(Looper.getMainLooper()))

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            mRequestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        val dpyMgr = Objects.requireNonNull(
            getSystemService(DISPLAY_SERVICE) as DisplayManager
        )
        dpyMgr.unregisterDisplayListener(mDisplayListener)
        mRenderer.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get().also { it.unbindAll() }

            val preview = Preview.Builder().build()
            mRenderer.attachInputPreview(preview).addListener(
                { Log.d(TAG, "OpenGLRenderer get the new surface for the Preview") },
                ContextCompat.getMainExecutor(this)
            )
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
        }, ContextCompat.getMainExecutor(this))
    }

    // **************************** Permission handling code start *******************************//
    private val mRequestPermissions = registerForActivityResult(
        RequestMultiplePermissions()
    ) { result ->
        for (permission: String in REQUIRED_PERMISSIONS) {
            if (result[permission] == true) {
                Toast.makeText(
                    this@OpenGLActivity, "Permissions not granted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        // All permissions granted.
        startCamera()
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
        private const val TAG = "OpenGLActivity"

        /**
         * Intent Extra string for choosing which Camera implementation to use.
         */
        const val INTENT_EXTRA_CAMERA_IMPLEMENTATION = "camera_implementation"

        /**
         * Intent Extra string for choosing which type of render surface to use to display Preview.
         */
        const val INTENT_EXTRA_RENDER_SURFACE_TYPE = "render_surface_type"

        /**
         * TextureView render surface for [OpenGLActivity.INTENT_EXTRA_RENDER_SURFACE_TYPE].
         * This is the default render surface.
         */
        const val RENDER_SURFACE_TYPE_TEXTUREVIEW = "textureview"

        /**
         * SurfaceView render surface for [OpenGLActivity.INTENT_EXTRA_RENDER_SURFACE_TYPE].
         * This type will block the main thread while detaching it's [Surface] from the OpenGL
         * renderer to avoid compatibility issues on some devices.
         */
        const val RENDER_SURFACE_TYPE_SURFACEVIEW = "surfaceview"

        /**
         * SurfaceView render surface (in non-blocking mode) for
         * [OpenGLActivity.INTENT_EXTRA_RENDER_SURFACE_TYPE]. This type will NOT
         * block the main thread while detaching it's [Surface] from the OpenGL
         * renderer, but some devices may crash due to their OpenGL/EGL implementation not being
         * thread-safe.
         */
        const val RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING = "surfaceview_nonblocking"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val FPS_NUM_SAMPLES = 10

        /**
         * Chooses the type of view to use for the viewfinder based on intent extras.
         *
         * @param intentExtras   Optional extras which can contain an extra with key
         * [.INTENT_EXTRA_RENDER_SURFACE_TYPE]. Possible values are one of
         * [.RENDER_SURFACE_TYPE_TEXTUREVIEW],
         * [.RENDER_SURFACE_TYPE_SURFACEVIEW], or
         * [.RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING]. If `null`,
         * or the bundle does not contain a surface type, then
         * [.RENDER_SURFACE_TYPE_TEXTUREVIEW] will be used.
         * @param viewFinderStub The stub to inflate the chosen viewfinder into.
         * @param renderer       The [OpenGLRenderer] which will render frames into the
         * viewfinder.
         * @return The inflated viewfinder View.
         */
        fun chooseViewFinder(
            intentExtras: Bundle?,
            viewFinderStub: ViewStub,
            renderer: OpenGLRenderer
        ): View {
            // By default we choose TextureView to maximize compatibility.
            var renderSurfaceType: String? = RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING
            if (intentExtras != null) {
                renderSurfaceType = intentExtras.getString(
                    INTENT_EXTRA_RENDER_SURFACE_TYPE,
                    RENDER_SURFACE_TYPE_TEXTUREVIEW
                )
            }
            return when (renderSurfaceType) {
                RENDER_SURFACE_TYPE_TEXTUREVIEW -> {
                    Log.d(TAG, "Using TextureView render surface.")
                    TextureViewRenderSurface.inflateWith(viewFinderStub, renderer)
                }
                RENDER_SURFACE_TYPE_SURFACEVIEW -> {
                    Log.d(TAG, "Using SurfaceView render surface.")
                    SurfaceViewRenderSurface.inflateWith(viewFinderStub, renderer)
                }
                RENDER_SURFACE_TYPE_SURFACEVIEW_NONBLOCKING -> {
                    Log.d(TAG, "Using SurfaceView (non-blocking) render surface.")
                    SurfaceViewRenderSurface.inflateNonBlockingWith(viewFinderStub, renderer)
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
