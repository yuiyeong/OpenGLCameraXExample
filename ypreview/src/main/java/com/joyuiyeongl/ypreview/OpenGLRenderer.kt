package com.joyuiyeongl.ypreview

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.util.Consumer
import androidx.core.util.Pair
import com.google.common.util.concurrent.ListenableFuture
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class OpenGLRenderer {
    companion object {
        private val TAG = "OpenGLRenderer"
        private val DEBUG = false
        private val RENDERER_COUNT = AtomicInteger(0)

        // Vectors defining the 'up' direction for the 4 angles we're interested in. These are based
        // off our world-space coordinate system (sensor coordinates), where the origin (0, 0) is in
        // the upper left of the image, and rotations are clockwise (left-handed coordinates).
        private val DIRECTION_UP_ROT_0 = floatArrayOf(0f, -1f, 0f, 0f)
        private val DIRECTION_UP_ROT_90 = floatArrayOf(1f, 0f, 0f, 0f)
        private val DIRECTION_UP_ROT_180 = floatArrayOf(0f, 1f, 0f, 0f)
        private val DIRECTION_UP_ROT_270 = floatArrayOf(-1f, 0f, 0f, 0f)
        private fun printMatrix(label: String, matrix: FloatArray, offset: Int) {
            Log.d(
                TAG, String.format(
                    "%s:\n"
                            + "%.4f %.4f %.4f %.4f\n"
                            + "%.4f %.4f %.4f %.4f\n"
                            + "%.4f %.4f %.4f %.4f\n"
                            + "%.4f %.4f %.4f %.4f\n",
                    label,
                    matrix[offset],
                    matrix[offset + 4],
                    matrix[offset + 8],
                    matrix[offset + 12],
                    matrix[offset + 1],
                    matrix[offset + 5],
                    matrix[offset + 9],
                    matrix[offset + 13],
                    matrix[offset + 2],
                    matrix[offset + 6],
                    matrix[offset + 10],
                    matrix[offset + 14],
                    matrix[offset + 3],
                    matrix[offset + 7],
                    matrix[offset + 11],
                    matrix[offset + 15]
                )
            )
        }

        init {
            System.loadLibrary("opengl_renderer_jni")
        }
    }

    private val mExecutor = SingleThreadHandlerExecutor(
        String.format(Locale.US, "GLRenderer-%03d", RENDERER_COUNT.incrementAndGet()),
        Process.THREAD_PRIORITY_DEFAULT
    ) // Use UI thread priority (DEFAULT)

    private var mPreviewTexture: SurfaceTexture? = null
    private var mPreviewCropRect: RectF? = null
    private var mPreviewSize: Size? = null
    private var mTextureRotationDegrees = 0

    // Transform retrieved by SurfaceTexture.getTransformMatrix
    private val mTextureTransform = FloatArray(16)

    // The Model represent the surface we are drawing on. In 3D, it is a flat rectangle.
    private val mModelTransform = FloatArray(16)
    private val mViewTransform = FloatArray(16)
    private val mProjectionTransform = FloatArray(16)

    // A combination of the model, view and projection transform matrices.
    private val mMvpTransform = FloatArray(16)
    private var mMvpDirty = true
    private var mSurfaceSize: Size? = null
    private var mSurfaceRotationDegrees = 0
    private val mTempVec = FloatArray(4)
    private val mTempMatrix = FloatArray(32) // 2 concatenated matrices for calculations
    private var mNativeContext: Long = 0
    private var mIsShutdown = false
    private var mNumOutstandingSurfaces = 0
    private var mFrameUpdateListener: Pair<Executor, Consumer<Long>>? = null


    @WorkerThread
    private external fun initContext(): Long

    @WorkerThread
    private external fun setWindowSurface(nativeContext: Long, surface: Surface?): Boolean

    @WorkerThread
    private external fun getTexName(nativeContext: Long): Int

    @WorkerThread
    private external fun renderTexture(
        nativeContext: Long,
        timestampNs: Long,
        mvpTransform: FloatArray,
        mvpDirty: Boolean,
        textureTransform: FloatArray
    ): Boolean

    @WorkerThread
    private external fun closeContext(nativeContext: Long)


    init {
        // Initialize the GL context on the GL thread
        mExecutor.execute { mNativeContext = initContext() }
    }

    /**
     * Attach the Preview to the renderer.
     *
     * @param preview Preview use-case used in the renderer.
     * @return A [ListenableFuture] that signals the new surface is ready to be used in the
     * renderer for the input Preview use-case.
     */
    @MainThread
    fun attachInputPreview(preview: Preview): ListenableFuture<Unit> {
        return CallbackToFutureAdapter.getFuture { completer ->
            preview.setSurfaceProvider(mExecutor, { surfaceRequest ->
                if (mIsShutdown) {
                    surfaceRequest.willNotProvideSurface()
                    return@setSurfaceProvider
                }

                val surfaceTexture: SurfaceTexture = resetPreviewTexture(surfaceRequest.resolution)
                val inputSurface: Surface = Surface(surfaceTexture)
                mNumOutstandingSurfaces++
                surfaceRequest.provideSurface(
                    inputSurface,
                    mExecutor,
                    { result: SurfaceRequest.Result? ->
                        inputSurface.release()
                        surfaceTexture.release()
                        if (surfaceTexture == mPreviewTexture) {
                            mPreviewTexture = null
                        }
                        mNumOutstandingSurfaces--
                        doShutdownExecutorIfNeeded()
                    })
                completer.set(null)
            })
            "attachInputPreview [$this]"
        }
    }

    fun attachOutputSurface(
        surface: Surface, surfaceSize: Size, surfaceRotationDegrees: Int
    ) {
        try {
            mExecutor.execute {
                if (mIsShutdown) {
                    return@execute
                }
                if (setWindowSurface(mNativeContext, surface)) {
                    if ((surfaceRotationDegrees != mSurfaceRotationDegrees
                                || !Objects.equals(surfaceSize, mSurfaceSize))
                    ) {
                        mMvpDirty = true
                    }
                    mSurfaceRotationDegrees = surfaceRotationDegrees
                    mSurfaceSize = surfaceSize
                } else {
                    mSurfaceSize = null
                }
            }
        } catch (e: RejectedExecutionException) {
            // Renderer is shutting down. Ignore.
        }
    }

    /**
     * Sets a listener to receive updates when a frame has been drawn to the output [Surface].
     *
     *
     * Frame updates include the timestamp of the latest drawn frame.
     *
     * @param executor Executor used to call the listener.
     * @param listener Listener which receives updates in the form of a timestamp (in nanoseconds).
     */
    fun setFrameUpdateListener(executor: Executor, listener: Consumer<Long>) {
        try {
            mExecutor.execute {
                mFrameUpdateListener =
                    Pair(
                        executor,
                        listener
                    )
            }
        } catch (e: RejectedExecutionException) {
            // Renderer is shutting down. Ignore.
        }
    }

    fun clearFrameUpdateListener() {
        try {
            mExecutor.execute { mFrameUpdateListener = null }
        } catch (e: RejectedExecutionException) {
            // Renderer is shutting down. Ignore.
        }
    }

    fun invalidateSurface(surfaceRotationDegrees: Int) {
        try {
            mExecutor.execute {
                if (surfaceRotationDegrees != mSurfaceRotationDegrees) {
                    mMvpDirty = true
                }
                mSurfaceRotationDegrees = surfaceRotationDegrees
                if (mPreviewTexture != null && !mIsShutdown) {
                    renderLatest()
                }
            }
        } catch (e: RejectedExecutionException) {
            // Renderer is shutting down. Ignore.
        }
    }

    /**
     * Detach the current output surface from the renderer.
     *
     * @return A [ListenableFuture] that signals detach from the renderer. Some devices may
     * not be able to handle the surface being released while still attached to an EGL context.
     * It should be safe to release resources associated with the output surface once this future
     * has completed.
     */
    fun detachOutputSurface(): ListenableFuture<Void> {
        return CallbackToFutureAdapter.getFuture { completer ->
            try {
                mExecutor.execute {
                    if (!mIsShutdown) {
                        setWindowSurface(mNativeContext, null)
                        mSurfaceSize = null
                    }
                    completer.set(null)
                }
            } catch (e: RejectedExecutionException) {
                // Renderer is shutting down. Can notify that the surface is detached.
                completer.set(null)
            }
            "detachOutputSurface [$this]"
        }
    }

    fun shutdown() {
        try {
            mExecutor.execute {
                if (!mIsShutdown) {
                    closeContext(mNativeContext)
                    mNativeContext = 0
                    mIsShutdown = true
                }
                doShutdownExecutorIfNeeded()
            }
        } catch (e: RejectedExecutionException) {
            // Renderer already shutting down. Ignore.
        }
    }

    @WorkerThread
    private fun doShutdownExecutorIfNeeded() {
        if (mIsShutdown && mNumOutstandingSurfaces == 0) {
            mFrameUpdateListener = null
            mExecutor.shutdown()
        }
    }

    @WorkerThread
    private fun resetPreviewTexture(size: Size): SurfaceTexture {
        if (mPreviewTexture != null) {
            mPreviewTexture!!.detachFromGLContext()
        }
        mPreviewTexture = SurfaceTexture(getTexName(mNativeContext))
        mPreviewTexture!!.setDefaultBufferSize(size.width, size.height)
        mPreviewTexture!!.setOnFrameAvailableListener(
            { surfaceTexture: SurfaceTexture ->
                if (surfaceTexture === mPreviewTexture && !mIsShutdown) {
                    surfaceTexture.updateTexImage()
                    renderLatest()
                }
            },
            mExecutor.handler
        )
        if (!(size == mPreviewSize)) {
            mMvpDirty = true
        }
        mPreviewSize = size
        return mPreviewTexture!!
    }

    @WorkerThread
    private fun renderLatest() {
        // Get the timestamp so we can pass it along to the output surface (not strictly necessary)
        val timestampNs = mPreviewTexture!!.timestamp

        // Get texture transform from surface texture (transform to natural orientation).
        // This will be used to transform texture coordinates in the fragment shader.
        mPreviewTexture!!.getTransformMatrix(mTextureTransform)
        // Check whether the texture's rotation has changed so we can update the MVP matrix.
        val textureRotationDegrees = textureRotationDegrees
        if (textureRotationDegrees != mTextureRotationDegrees) {
            mMvpDirty = true
        }
        mTextureRotationDegrees = textureRotationDegrees
        if (mSurfaceSize != null) {
            if (mMvpDirty) {
                updateMvpTransform()
            }
            val success = renderTexture(
                mNativeContext, timestampNs, mMvpTransform, mMvpDirty,
                mTextureTransform
            )
            mMvpDirty = false
            if (success && mFrameUpdateListener != null) {
                val executor = Objects.requireNonNull(
                    mFrameUpdateListener!!.first
                )
                val listener = Objects.requireNonNull(
                    mFrameUpdateListener!!.second
                )
                try {
                    executor.execute({ listener.accept(timestampNs) })
                } catch (e: RejectedExecutionException) {
                    // Unable to send frame update. Ignore.
                }
            }
        }
    }//       (0,1)
    //    +----^----+         270 deg         +---------+
    //    |    |    |        Rotation         |         |
    //    |    +    |         +----->   (-1,0)<----+    |
    //    |  (0,0)  |                         |  (0,0)  |
    //    +---------+                         +---------+
//       (0,1)
    //    +----^----+         180 deg         +---------+
    //    |    |    |        Rotation         |  (0,0)  |
    //    |    +    |         +----->         |    +    |
    //    |  (0,0)  |                         |    |    |
    //    +---------+                         +----v----+
    //                                           (0,-1)
//       (0,1)
    //    +----^----+         90 deg          +---------+
    //    |    |    |        Rotation         |         |
    //    |    +    |         +----->         |    +---->(1,0)
    //    |  (0,0)  |                         |  (0,0)  |
    //    +---------+                         +---------+
//       (0,1)                               (0,1)
    //    +----^----+          0 deg          +----^----+
    //    |    |    |        Rotation         |    |    |
    //    |    +    |         +----->         |    +    |
    //    |  (0,0)  |                         |  (0,0)  |
    //    +---------+                         +---------+
// The final output image should have the requested dimensions AFTER applying the
    // transform matrix, but width and height may be swapped. We know that the transform
    // matrix from SurfaceTexture#getTransformMatrix() is an affine transform matrix that
    // will only rotate in 90 degree increments, so we only need to worry about the rotation
    // component.
    //
    // We can test this by using an test vector of [s, t, p, q] = [0, 1, 0, 0]. Using 'q = 0'
    // will ignore the translation component of the matrix. We will only need to check if the
    // 's' component becomes a scaled version of the 't' component and the 't' component
    // becomes 0.

    // Calculate the normalized vector and round to integers so we can do integer comparison.
    // Normalizing the vector removes the effects of the scaling component of the
    // transform matrix. Once normalized, we can round and do integer comparison.
    /**
     * Calculates the rotation of the source texture between the sensor coordinate space and
     * the device's 'natural' orientation.
     *
     *
     * A required transform matrix is passed along with each texture update and is retrieved by
     * [SurfaceTexture.getTransformMatrix].
     *
     * <pre>`TEXTURE FROM SENSOR:
     * ^
     * |                  +-----------+
     * |          .#######|###        |
     * |           *******|***        |
     * |   ....###########|## ####. / |         Sensor may be rotated relative
     * |  ################|## #( )#.  |         to the device's 'natural'
     * |       ###########|## ######  |         orientation.
     * |  ################|## #( )#*  |
     * |   ****###########|## ####* \ |
     * |           .......|...        |
     * |          *#######|###        |
     * |                  +-----------+
     * +-------------------------------->
     * TRANSFORMED IMAGE:
     * | |                   ^
     * | |                   |         .            .
     * | |                   |         \\ ........ //
     * Transform matrix from               |         ##############
     * SurfaceTexture#getTransformMatrix() |       ###(  )####(  )###
     * performs scale/crop/rotate on       |      ####################
     * image from sensor to produce        |     ######################
     * image in 'natural' orientation.     | ..  ......................  ..
     * | |                   |#### ###################### ####
     * | +-------\           |#### ###################### ####
     * +---------/           |#### ###################### ####
     * +-------------------------------->
    `</pre> *
     *
     *
     * The transform matrix is a 4x4 affine transform matrix that operates on standard normalized
     * texture coordinates which are in the range of [0,1] for both s and t dimensions. Before
     * the transform is applied, the texture may have dimensions that are larger than the
     * dimensions of the SurfaceTexture we provided in order to accommodate hardware limitations.
     *
     *
     * For this method we are only interested in the rotation component of the transform
     * matrix, so the calculations avoid the scaling and translation components.
     */
    @get:WorkerThread
    private val textureRotationDegrees: Int
        get() {
            // The final output image should have the requested dimensions AFTER applying the
            // transform matrix, but width and height may be swapped. We know that the transform
            // matrix from SurfaceTexture#getTransformMatrix() is an affine transform matrix that
            // will only rotate in 90 degree increments, so we only need to worry about the rotation
            // component.
            //
            // We can test this by using an test vector of [s, t, p, q] = [0, 1, 0, 0]. Using 'q = 0'
            // will ignore the translation component of the matrix. We will only need to check if the
            // 's' component becomes a scaled version of the 't' component and the 't' component
            // becomes 0.
            Matrix.multiplyMV(mTempVec, 0, mTextureTransform, 0, DIRECTION_UP_ROT_0, 0)

            // Calculate the normalized vector and round to integers so we can do integer comparison.
            // Normalizing the vector removes the effects of the scaling component of the
            // transform matrix. Once normalized, we can round and do integer comparison.
            val length = Matrix.length(mTempVec[0], mTempVec[1], 0f)
            val s = Math.round(mTempVec[0] / length)
            val t = Math.round(mTempVec[1] / length)
            if (s == 0 && t == 1) {
                //       (0,1)                               (0,1)
                //    +----^----+          0 deg          +----^----+
                //    |    |    |        Rotation         |    |    |
                //    |    +    |         +----->         |    +    |
                //    |  (0,0)  |                         |  (0,0)  |
                //    +---------+                         +---------+
                return 0
            } else if (s == 1 && t == 0) {
                //       (0,1)
                //    +----^----+         90 deg          +---------+
                //    |    |    |        Rotation         |         |
                //    |    +    |         +----->         |    +---->(1,0)
                //    |  (0,0)  |                         |  (0,0)  |
                //    +---------+                         +---------+
                return 90
            } else if (s == 0 && t == -1) {
                //       (0,1)
                //    +----^----+         180 deg         +---------+
                //    |    |    |        Rotation         |  (0,0)  |
                //    |    +    |         +----->         |    +    |
                //    |  (0,0)  |                         |    |    |
                //    +---------+                         +----v----+
                //                                           (0,-1)
                return 180
            } else if (s == -1 && t == 0) {
                //       (0,1)
                //    +----^----+         270 deg         +---------+
                //    |    |    |        Rotation         |         |
                //    |    +    |         +----->   (-1,0)<----+    |
                //    |  (0,0)  |                         |  (0,0)  |
                //    +---------+                         +---------+
                return 270
            }
            throw RuntimeException(
                String.format(
                    ("Unexpected texture transform matrix. Expected "
                            + "test vector [0, 1] to rotate to [0,1], [1, 0], [0, -1] or [-1, 0], but instead "
                            + "was [%d, %d]."), s, t
                )
            )
        }

    /**
     * Returns true if the crop rect dimensions match the entire texture dimensions.
     */
    @WorkerThread
    private fun isCropRectFullTexture(cropRect: Rect): Boolean {
        return (cropRect.left == 0) && (cropRect.top == 0
                ) && (cropRect.width() == mPreviewSize!!.width
                ) && (cropRect.height() == mPreviewSize!!.height)
    }

    /**
     * Derives the model crop rect from the texture and output surface dimensions, applying a
     * 'center-crop' transform.
     *
     *
     * Because the camera sensor (or crop of the camera sensor) may have a different
     * aspect ratio than the ViewPort that is meant to display it, we want to fit the image
     * from the camera so the entire ViewPort is filled. This generally requires scaling the input
     * texture and cropping pixels from either the width or height. We call this transform
     * 'center-crop' and is equivalent to [android.widget.ImageView.ScaleType.CENTER_CROP].
     */
    @WorkerThread
    private fun extractPreviewCropFromPreviewSizeAndSurface() {
        // Swap the dimensions of the surface we are drawing the texture onto if rotating the
        // texture to the surface orientation requires a 90 degree or 270 degree rotation.
        val viewPortRotation = viewPortRotation
        if (viewPortRotation == 90 || viewPortRotation == 270) {
            // Width and height swapped
            mPreviewCropRect = RectF(
                0.0f, 0.0f, mSurfaceSize!!.height.toFloat(),
                mSurfaceSize!!.width.toFloat()
            )
        } else {
            mPreviewCropRect = RectF(
                0.0f, 0.0f, mSurfaceSize!!.width.toFloat(),
                mSurfaceSize!!.height.toFloat()
            )
        }
        val centerCropMatrix = android.graphics.Matrix()
        val previewSize = RectF(
            0.0f, 0.0f, mPreviewSize!!.width.toFloat(),
            mPreviewSize!!.height.toFloat()
        )
        centerCropMatrix.setRectToRect(
            mPreviewCropRect, previewSize,
            android.graphics.Matrix.ScaleToFit.CENTER
        )
        centerCropMatrix.mapRect(mPreviewCropRect)
    }// Note that since the rotation defined by Surface#ROTATION_*** are positive when the
    // device is rotated in a counter-clockwise direction and our world-space coordinates
    // define positive angles in the clockwise direction, we add the two together to get the
    // total angle required.
    /**
     * Returns the relative rotation between the sensor coordinates and the ViewPort in
     * world-space coordinates.
     *
     *
     * This is the angle the sensor needs to be rotated, clockwise, in order to be upright in
     * the viewport coordinates.
     */
    @get:WorkerThread
    private val viewPortRotation: Int
        get() =// Note that since the rotation defined by Surface#ROTATION_*** are positive when the
        // device is rotated in a counter-clockwise direction and our world-space coordinates
        // define positive angles in the clockwise direction, we add the two together to get the
            // total angle required.
            (mTextureRotationDegrees + mSurfaceRotationDegrees) % 360

    /**
     * Updates the matrix used to transform the model into the correct dimensions within the
     * world-space.
     *
     *
     * In order to draw the camera frames to screen, we use a flat rectangle in our
     * world-coordinate space. The world coordinates match the preview buffer coordinates with
     * the origin (0,0) in the upper left corner of the image. Defining the world space in this
     * way allows subsequent models to be positioned according to buffer coordinates.
     * Note this different than standard OpenGL coordinates; this is a left-handed coordinate
     * system, and requires using glFrontFace(GL_CW) before drawing.
     * <pre>`Standard coordinates:                   Our coordinate system:
     *
     * | +y                                  ________+x
     * |                                   /|
     * |                                  / |
     * |________+x                     +z/  |
     * /                                     | +y
     * /
     * /+z
    `</pre> *
     *
     * Our model is initially a square with vertices in the range (-1,-1 - 1,1). It is
     * rotated, scaled and translated to match the dimensions of preview with the origin in the
     * upper left corner.
     *
     *
     * Example for a preview with dimensions 1920x1080:
     * <pre>`(-1,-1)    (1,-1)
     * +---------+        Model
     * |         |        Transform          (0,0)         (1920,0)
     * Unscaled Model -> |    +    |         ---\                +----------------+
     * |         |         ---/                |                |      Scaled/
     * +---------+                             |                | <-- Translated
     * (-1,1)     (1,1)                           |                |       Model
     * +----------------+
     * (0,1080)      (1920,1080)
    `</pre> *
     */
    @WorkerThread
    private fun updateModelTransform() {
        // Remove the rotation to the device 'natural' orientation so our world space will be in
        // sensor coordinates.
        Matrix.setRotateM(mTempMatrix, 0, -mTextureRotationDegrees.toFloat(), 0.0f, 0.0f, 1.0f)
        Matrix.setIdentityM(mTempMatrix, 16)
        // Translate to the upper left corner of the quad so we are in buffer space
        Matrix.translateM(
            mTempMatrix, 16, mPreviewSize!!.width / 2f,
            mPreviewSize!!.height / 2f, 0f
        )
        // Scale the vertices so that our world space units are pixels equal in size to the
        // pixels of the buffer sent from the camera.
        Matrix.scaleM(
            mTempMatrix, 16, mPreviewSize!!.width / 2f, mPreviewSize!!.height / 2f,
            1f
        )
        Matrix.multiplyMM(mModelTransform, 0, mTempMatrix, 16, mTempMatrix, 0)
        if (DEBUG) {
            printMatrix("ModelTransform", mModelTransform, 0)
        }
    }

    /**
     * The view transform defines the position and orientation of the camera within our world-space.
     *
     *
     * This brings us from world-space coordinates to view (camera) space.
     *
     *
     * This matrix is defined by a camera position, a gaze point, and a vector that represents
     * the "up" direction. Because we are using an orthogonal projection, we always place the
     * camera directly in front of the gaze point and 1 unit away on the z-axis for convenience.
     * We have defined our world coordinates in a way where we will be looking at the front of
     * the model rectangle if our camera is placed on the positive z-axis and we gaze towards
     * the negative z-axis.
     */
    @WorkerThread
    private fun updateViewTransform() {
        // Apply the rotation of the ViewPort and look at the center of the image
        var upVec = DIRECTION_UP_ROT_0
        when (viewPortRotation) {
            0 -> upVec = DIRECTION_UP_ROT_0
            90 -> upVec = DIRECTION_UP_ROT_90
            180 -> upVec = DIRECTION_UP_ROT_180
            270 -> upVec = DIRECTION_UP_ROT_270
        }
        Matrix.setLookAtM(
            mViewTransform, 0,
            mPreviewCropRect!!.centerX(), mPreviewCropRect!!.centerY(), 1f,  // Camera position
            mPreviewCropRect!!.centerX(), mPreviewCropRect!!.centerY(), 0f,  // Point to look at
            upVec[0], upVec[1], upVec[2] // Up direction
        )
        if (DEBUG) {
            printMatrix("ViewTransform", mViewTransform, 0)
        }
    }

    /**
     * The projection matrix will map from the view space to normalized device coordinates (NDC)
     * which OpenGL is expecting.
     *
     *
     * Our view is meant to only show the pixels defined by the model crop rect, so our
     * orthogonal projection matrix will depend on the preview crop rect dimensions.
     *
     *
     * The projection matrix can be thought of as a cube which has sides that align with the
     * edges of the ViewPort and the near/far sides can be adjusted as needed. In our case, we
     * set the near side to match the camera position and the far side to match the model's
     * position on the z-axis, 1 unit away.
     */
    @WorkerThread
    private fun updateProjectionTransform() {
        var viewPortWidth = mPreviewCropRect!!.width()
        var viewPortHeight = mPreviewCropRect!!.height()
        // Since projection occurs after rotation of the camera, in order to map directly to model
        // coordinates we need to take into account the surface rotation.
        val viewPortRotation = viewPortRotation
        if (viewPortRotation == 90 || viewPortRotation == 270) {
            viewPortWidth = mPreviewCropRect!!.height()
            viewPortHeight = mPreviewCropRect!!.width()
        }
        Matrix.orthoM(
            mProjectionTransform, 0,  /*left=*/
            -viewPortWidth / 2f,  /*right=*/viewPortWidth / 2f,  /*bottom=*/
            viewPortHeight / 2f,  /*top=*/-viewPortHeight / 2f, 0f, 1f
        )
        if (DEBUG) {
            printMatrix("ProjectionTransform", mProjectionTransform, 0)
        }
    }

    /**
     * The MVP is the combination of model, view and projection transforms that take us from the
     * world space to normalized device coordinates (NDC) which OpenGL uses to display images
     * with the correct dimensions on an EGL surface.
     */
    @WorkerThread
    private fun updateMvpTransform() {
        if (mPreviewCropRect == null) {
            extractPreviewCropFromPreviewSizeAndSurface()
        }
        if (DEBUG) {
            Log.d(
                TAG, String.format(
                    "Model dimensions: %s, Crop rect: %s", mPreviewSize,
                    mPreviewCropRect
                )
            )
        }
        updateModelTransform()
        updateViewTransform()
        updateProjectionTransform()
        Matrix.multiplyMM(mTempMatrix, 0, mViewTransform, 0, mModelTransform, 0)
        if (DEBUG) {
            // Print the model-view matrix (without projection)
            printMatrix("MVTransform", mTempMatrix, 0)
        }
        Matrix.multiplyMM(mMvpTransform, 0, mProjectionTransform, 0, mTempMatrix, 0)
        if (DEBUG) {
            printMatrix("MVPTransform", mMvpTransform, 0)
        }
    }
}
