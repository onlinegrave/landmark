package com.mapking.ar.core.landmark.geo

import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.mapking.ar.core.landmark.geo.common.helpers.*
import com.mapking.ar.core.landmark.geo.common.helpers.CameraPermissionHelper.Companion.hasCameraPermission
import com.mapking.ar.core.landmark.geo.common.helpers.CameraPermissionHelper.Companion.launchPermissionSettings
import com.mapking.ar.core.landmark.geo.common.helpers.CameraPermissionHelper.Companion.requestCameraPermission
import com.mapking.ar.core.landmark.geo.common.helpers.CameraPermissionHelper.Companion.shouldShowRequestPermissionRationale
import com.mapking.ar.core.landmark.geo.common.rendering.BackgroundRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.ObjectRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.PlaneRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.PointCloudRenderer
import com.mapking.ar.core.landmark.geo.databinding.ActivitySharedCameraBinding
import com.mapking.ar.core.landmark.geo.helpers.camera.OrientationLiveData
import com.mapking.ar.core.landmark.geo.helpers.camera.computeExifOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 * This is a simple example that demonstrates how to use the Camera2 API while sharing camera access
 * with ARCore. An on-screen switch can be used to pause and resume ARCore. The app utilizes a
 * trivial sepia camera effect while ARCore is paused, and seamlessly hands camera capture request
 * control over to ARCore when it is running.
 *
 *
 * This app demonstrates:
 *
 *
 *  * Starting in AR or non-AR mode by setting the initial value of `arMode`
 *  * Toggling between non-AR and AR mode using an on screen switch
 *  * Pausing and resuming the app while in AR or non-AR mode
 *  * Requesting CAMERA_PERMISSION when app starts, and each time the app is resumed
 *
 */
class SharedCameraActivity : AppCompatActivity(), GLSurfaceView.Renderer,
    OnImageAvailableListener, OnFrameAvailableListener {
    // Whether the app is currently in AR mode. Initial value determines initial state.
    private var arMode = false

    private var _activitySharedCameraBinding: ActivitySharedCameraBinding? = null

    private val activitySharedCameraBiding get() = _activitySharedCameraBinding!!

    // Whether the app has just entered non-AR mode.
    private val isFirstFrameWithoutArcore = AtomicBoolean(true)

    // GL Surface used to draw camera preview image.
    private var surfaceView: GLSurfaceView? = null

    // Text view for displaying on screen status message.
    private var statusTextView: TextView? = null

    // Linear layout that contains preview image and status text.
    private var imageTextLinearLayout: LinearLayout? = null

    // ARCore session that supports camera sharing.
    private var sharedSession: Session? = null

    // Camera capture session. Used by both non-AR and AR modes.
    private var captureSession: CameraCaptureSession? = null

    // A list of CaptureRequest keys that can cause delays when switching between AR and non-AR modes.
    private var keysThatCanCauseCaptureDelaysWhenModified: List<CaptureRequest.Key<*>>? = null

    // Camera device. Used by both non-AR and AR modes.
    private var cameraDevice: CameraDevice? = null

    // Looper handler thread.
    private var backgroundThread: HandlerThread? = null

    // Looper handler.
    private var backgroundHandler: Handler? = null

    // ARCore shared camera instance, obtained from ARCore session that supports sharing.
    private var sharedCamera: SharedCamera? = null

    // Camera ID for the camera used by ARCore.
    private var cameraId: String? = null

    // Pixel Format for the camera used by ARCore.
    private var pixelFormat: Int? = null

    // Ensure GL surface draws only occur when new frames are available.
    private val shouldUpdateSurfaceTexture = AtomicBoolean(false)

    // Whether ARCore is currently active.
    private var arcoreActive = false

    // Whether the GL surface has been created.
    private var surfaceCreated = false

    /**
     * Whether an error was thrown during session creation.
     */
    private var errorCreatingSession = false

    // Camera preview capture request builder
    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null

    // Image reader that continuously processes CPU images.
    private var cpuImageReader: ImageReader? = null

    // Total number of CPU images processed.
    private var cpuImagesProcessed = 0

    // Various helper classes, see hello_ar_java sample to learn more.
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper = TrackingStateHelper(this)
    private var tapHelper: TapHelper? = null

    // Renderers, see hello_ar_java sample to learn more.
    private val backgroundRenderer = BackgroundRenderer()
    private val virtualObject = ObjectRenderer()
    private val virtualObjectShadow = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    // Anchors created from taps, see hello_ar_java sample to learn more.
    private val anchors = ArrayList<ColoredAnchor>()
    private val automatorRun = AtomicBoolean(false)

    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private var captureSessionChangesPossible = true

    // A check mechanism to ensure that the camera closed properly so that the app can safely exit.
    private val safeToExitApp = ConditionVariable()

    private class ColoredAnchor(val anchor: Anchor, val color: FloatArray)

    private val cameraManager: CameraManager by lazy {
        val context = applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId!!)
    }


    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            activitySharedCameraBiding.overlay.background =
                Color.argb(150, 255, 255, 255).toDrawable()
            activitySharedCameraBiding.overlay.postDelayed({
                //Remove white flash animation
                activitySharedCameraBiding.overlay.background = null
            }, ANIMATION_FAST_MILLIS)

        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this activity */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSessio] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listenere for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData


    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE] template. it performs synchronization between
     * the [CaptureResult] and the [Image] resulting from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto(): CombinedCaptureResult = suspendCoroutine { cont ->
        //Flush any images left in the image reader
        while (imageReader.acquireLatestImage() != null) {
        }

        //Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(imageReader.surface)
        }
        session.capture(
            captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    activitySharedCameraBiding.glsurfaceview.post(animationTask)
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    Log.d(TAG, "Capture result received: $resultTimestamp")

                    // Set a timeout in case image captured is dropped from the pipeline
                    val exc = TimeoutException("Image dequeing took too long")
                    val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                    imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)


                    // Loop in the coroutine's context until an image with matching timestamp comes
                    // We need to launch the coroutine context again because the callback is done in
                    // the handler provide to the `capture` method, not in our coroutine context
                    lifecycleScope.launch(cont.context) {
                        while (true) {

                            // Dequeue images while timestamps don't match
                            val image = imageQueue.take()
                            // TODO missing
                            // if (image.timestamp != resultTimestamp) continue
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && image.format != ImageFormat.DEPTH_JPEG && image.timestamp != resultTimestamp) continue
                            Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                            // Unset the image reader listener
                            imageReaderHandler.removeCallbacks(timeoutRunnable)
                            imageReader.setOnImageAvailableListener(null, null)

                            // Clear the queue of images, if there are left
                            while (imageQueue.size > 0) {
                                imageQueue.take().close()
                            }

                            //Compute EXIF orientation metadata
                            val rotation = relativeOrientation.value ?: 0
                            val mirrored =
                                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                            val exifOrientation = computeExifOrientation(rotation, mirrored)

                            // Build the result and resume progress
                            cont.resume(
                                CombinedCaptureResult(
                                    image, result, exifOrientation, imageReader.imageFormat
                                )
                            )
                            // There is not need to break out of the loop, this coroutine will suspend

                        }
                    }
                }
            }, cameraHandler
        )
    }

    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                try {
                    val output = createFile(this, "jpg")
                    FileOutputStream(output).use { it.write(bytes) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file ", exc)
                    cont.resumeWithException(exc)
                }
            }

            //When the format is RAW we use the DngCreate utility library
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                try {
                    val output = createFile(this, "dng")
                    FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                    cont.resume(output)
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write to DNG image to file: ", exc)
                    cont.resumeWithException(exc)
                }
            }

            // TODO Write other supported format here
            // No other formats are supported
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

        }
    }

    // Camera device state callback.
    private val cameraDeviceCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " opened.")
                this@SharedCameraActivity.cameraDevice = cameraDevice
                createCameraPreviewSession()
            }

            override fun onClosed(cameraDevice: CameraDevice) {
                Log.d(TAG, "Camera device ID " + cameraDevice.id + " closed.")
                this@SharedCameraActivity.cameraDevice = null
                safeToExitApp.open()
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                Log.w(TAG, "Camera device ID " + cameraDevice.id + " disconnected.")
                cameraDevice.close()
                this@SharedCameraActivity.cameraDevice = null
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device ID " + cameraDevice.id + " error " + error)
                cameraDevice.close()
                this@SharedCameraActivity.cameraDevice = null
                // Fatal error. Quit application.
                finish()
            }
        }

    // Repeating camera capture session state callback.
    private var cameraSessionStateCallback: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            // Called when the camera capture session is first configured after the app
            // is initialized, and again each time the activity is resumed.
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session configured.")
                captureSession = session
                if (arMode) {
                    setRepeatingCaptureRequest()
                    // Note, resumeARCore() must be called in onActive(), not here.
                } else {
                    // Calls `setRepeatingCaptureRequest()`.
                    resumeCamera2()
                }
            }

            override fun onSurfacePrepared(
                session: CameraCaptureSession, surface: Surface
            ) {
                Log.d(TAG, "Camera capture surface prepared.")
            }

            override fun onReady(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session ready.")
            }

            override fun onActive(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session active.")
                if (arMode && !arcoreActive) {
                    resumeARCore()
                }
                synchronized(this@SharedCameraActivity) {
                    captureSessionChangesPossible = true
                    // TODO
//                    this@SharedCameraActivity.notify()
                }
                updateSnackbarMessage()
            }

            override fun onCaptureQueueEmpty(session: CameraCaptureSession) {
                Log.w(TAG, "Camera capture queue empty.")
            }

            override fun onClosed(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session closed.")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session.")
            }
        }

    // Repeating camera capture session capture callback.
    private val cameraCaptureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            shouldUpdateSurfaceTexture.set(true)
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {
            Log.e(
                TAG,
                "onCaptureBufferLost: $frameNumber"
            )
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "onCaptureFailed: " + failure.frameNumber + " " + failure.reason)
        }

        override fun onCaptureSequenceAborted(
            session: CameraCaptureSession, sequenceId: Int
        ) {
            Log.e(
                TAG,
                "onCaptureSequenceAborted: $sequenceId $session"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _activitySharedCameraBinding = ActivitySharedCameraBinding.inflate(layoutInflater)
        setContentView(activitySharedCameraBiding.root)
        val extraBundle = intent.extras
        if (extraBundle != null && 1 == extraBundle.getShort(AUTOMATOR_KEY, AUTOMATOR_DEFAULT)
                .toInt()
        ) {
            automatorRun.set(true)
        }

        activitySharedCameraBiding.captureButton.setOnClickListener {
            // Disable click listener to prevent multiple request simultaneously in flight
            it.isEnabled = false

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    //Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update the EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            result.orientation.toString()
                        )
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")

                    }

                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        // TODO
                    }

                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }

        // GL surface view that renders camera preview image.
        surfaceView = findViewById<View>(R.id.glsurfaceview) as GLSurfaceView
        surfaceView!!.preserveEGLContextOnPause = true
        surfaceView!!.setEGLContextClientVersion(3)
        surfaceView!!.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView!!.setRenderer(this)
        surfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Helpers, see hello_ar_java sample to learn more.
        displayRotationHelper = DisplayRotationHelper(this)
        tapHelper = TapHelper(this)
        surfaceView!!.setOnTouchListener(tapHelper)
        imageTextLinearLayout = findViewById(R.id.image_text_layout)
        statusTextView = findViewById(R.id.text_view)

        // Switch to allow pausing and resuming of ARCore.
        val arcoreSwitch = findViewById<View>(R.id.arcore_switch) as SwitchMaterial
        // Ensure initial switch position is set based on initial value of `arMode` variable.
        arcoreSwitch.isChecked = arMode
        arcoreSwitch.setOnCheckedChangeListener { view: CompoundButton?, checked: Boolean ->
            Log.i(
                TAG,
                "Switching to " + (if (checked) "AR" else "non-AR") + " mode."
            )
            if (checked) {
                arMode = true
                resumeARCore()
            } else {
                arMode = false
                pauseARCore()
                resumeCamera2()
            }
            updateSnackbarMessage()
        }
        messageSnackbarHelper.setMaxLines(4)
        updateSnackbarMessage()
    }

    override fun onDestroy() {
        if (sharedSession != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            sharedSession!!.close()
            sharedSession = null
        }
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        super.onDestroy()
    }

    @Synchronized
    private fun waitUntilCameraCaptureSessionIsActive() {
        while (!captureSessionChangesPossible) {
            try {
                // TODO
//                this.wait()
            } catch (e: InterruptedException) {
                Log.e(
                    TAG,
                    "Unable to wait for a safe time to make changes to the capture session",
                    e
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        waitUntilCameraCaptureSessionIsActive()
        startBackgroundThread()
        surfaceView!!.onResume()

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (surfaceCreated) {
            openCamera()
        }
        displayRotationHelper!!.onResume()
    }

    public override fun onPause() {
        shouldUpdateSurfaceTexture.set(false)
        surfaceView!!.onPause()
        waitUntilCameraCaptureSessionIsActive()
        displayRotationHelper!!.onPause()
        if (arMode) {
            pauseARCore()
        }
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun resumeCamera2() {
        setRepeatingCaptureRequest()
        sharedCamera!!.surfaceTexture.setOnFrameAvailableListener(this)
    }

    private fun resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (sharedSession == null) {
            return
        }
        if (!arcoreActive) {
            try {
                // To avoid flicker when resuming ARCore mode inform the renderer to not suppress rendering
                // of the frames with zero timestamp.
                backgroundRenderer.suppressTimestampZeroRendering(false)
                // Resume ARCore.
                sharedSession!!.resume()
                arcoreActive = true
                updateSnackbarMessage()

                // Set capture session callback while in AR mode.
                sharedCamera!!.setCaptureCallback(cameraCaptureCallback, backgroundHandler)
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Failed to resume ARCore session", e)
                return
            }
        }
    }

    private fun pauseARCore() {
        if (arcoreActive) {
            // Pause ARCore.
            sharedSession!!.pause()
            isFirstFrameWithoutArcore.set(true)
            arcoreActive = false
            updateSnackbarMessage()
        }
    }

    private fun updateSnackbarMessage() {
        messageSnackbarHelper.showMessage(
            this,
            if (arcoreActive) "ARCore is active.\nSearch for plane, then tap to place a 3D model." else "ARCore is paused.\nCamera effects enabled."
        )
    }

    // Called when starting non-AR mode or switching to non-AR mode.
    // Also called when app starts in AR mode, or resumes in AR mode.
    private fun setRepeatingCaptureRequest() {
        try {
            setCameraEffects(previewCaptureRequestBuilder)
            captureSession!!.setRepeatingRequest(
                previewCaptureRequestBuilder!!.build(),
                cameraCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to set repeating request", e)
        }
    }

    private fun createCameraPreviewSession() {
        try {
            sharedSession!!.setCameraTextureName(backgroundRenderer.textureId)
            sharedCamera!!.surfaceTexture.setOnFrameAvailableListener(this)

            // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
            previewCaptureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            // Build surfaces list, starting with ARCore provided surfaces.
            val surfaceList = sharedCamera!!.arCoreSurfaces

            // Add a CPU image reader surface. On devices that don't support CPU image access, the image
            // may arrive significantly later, or not arrive at all.
            surfaceList.add(cpuImageReader!!.surface)

            // Surface list should now contain three surfaces:
            // 0. sharedCamera.getSurfaceTexture()
            // 1. …
            // 2. cpuImageReader.getSurface()

            // Add ARCore surfaces and CPU image surface targets.
            for (surface in surfaceList) {
                previewCaptureRequestBuilder!!.addTarget(surface)
            }

            // Wrap our callback in a shared camera callback.
            val wrappedCallback = sharedCamera!!.createARSessionStateCallback(
                cameraSessionStateCallback,
                backgroundHandler
            )

            // Create camera capture session for camera preview using ARCore wrapped callback.
            cameraDevice!!.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException", e)
        }
    }

    // Start background handler thread, used to run callbacks without blocking UI thread.
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("sharedCameraBackground")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Stop background handler thread.
    private fun stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread!!.quitSafely()
            try {
                backgroundThread!!.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Interrupted while trying to join background handler thread", e)
            }
        }
    }

    /**
     *  Begin all camera operation in a coroutine in the main thread. This function:
     *  - Opens the camera
     *  - Configure the camera session
     *  - Starts the preview by dispatching a repeating capture request
     *  - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open camera
        camera = openCamera(cameraManager, cameraId!!, cameraHandler)

        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(pixelFormat!!).maxByOrNull { it.height * it.width }!!
        imageReader =
            ImageReader.newInstance(size.width, size.height, pixelFormat!!, IMAGE_BUFFER_SIZE)

        // Creates list of surefaces where the camera will output frames
//        val targets: List<Surface> =
//            listOf(activitySharedCameraBiding.glsurfaceview, imageReader.surface)
        val targets: List<Surface> =
            listOf(imageReader.surface)

        //Start a cpature session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
//                addTarget(activitySharedCameraBiding.glsurfaceview)
            }

        // This will keep sending the capture request as frequently as possible until the session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        // Listen to the capture button
        activitySharedCameraBiding.captureButton.setOnClickListener {
            Log.d(TAG, "Capture Button Click: ")
            // Disable click listener to prevent multiple request simultaneously in flight
            it.isEnabled = false

            // Perform I/O heavy operations in a different scope
            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { result ->
                    Log.d(TAG, "Result received: $result")

                    //Save the result to disk
                    val output = saveResult(result)
                    Log.d(TAG, "Image saved: ${output.absolutePath}")

                    // If the result is a JPEG file, update the EXIF metadata with orientation info
                    if (output.extension == "jpg") {
                        val exif = ExifInterface(output.absolutePath)
                        exif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            result.orientation.toString()
                        )
                        exif.saveAttributes()
                        Log.d(TAG, "EXIF metadata saved: ${output.absolutePath}")

                    }

                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        // TODO
                    }

                }

                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice, targets: List<Surface>, handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        // Create a capture session using the predefined targets; this also involves defining the session state callback to be notifierd of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera $device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }

            override fun onConfigured(session: CameraCaptureSession) {
                cont.resume(session)
            }
        }, handler)
    }

    /** Opens the camera and returns the opend device (as the result of the suspend coroutine) */
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(p0: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                finish();
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }

                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    // Perform various checks, then open camera device and create CPU image reader.
    private fun openCamera() {
        // Don't open camera if already opened.
        if (cameraDevice != null) {
            return
        }

        // Verify CAMERA_PERMISSION has been granted.
        if (!hasCameraPermission(this)) {
            requestCameraPermission(this)
            return
        }

        // Make sure that ARCore is installed, up to date, and supported on this device.
        if (!isARCoreSupportedAndUpToDate) {
            return
        }
        if (sharedSession == null) {
            try {
                // Create ARCore session that supports camera sharing.
                sharedSession = Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA))
            } catch (e: Exception) {
                errorCreatingSession = true
                messageSnackbarHelper.showError(
                    this, "Failed to create ARCore session that supports camera sharing"
                )
                Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e)
                return
            }
            errorCreatingSession = false

            // Enable auto focus mode while ARCore is running.
            val config = sharedSession!!.config
            config.focusMode = Config.FocusMode.AUTO
            sharedSession!!.configure(config)
        }


        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(pixelFormat!!).maxByOrNull { it.height * it.width }!!
        imageReader =
            ImageReader.newInstance(size.width, size.height, pixelFormat!!, IMAGE_BUFFER_SIZE)

        // Creates list of surefaces where the camera will output frames
//        val targets: List<Surface> =
//            listOf(activitySharedCameraBiding.glsurfaceview, imageReader.surface)
        val targets: List<Surface> =
            listOf(imageReader.surface)

        lifecycleScope.launch(Dispatchers.Main) {
            //Start a cpature session using our open camera and list of Surfaces where frames will go
            session = createCaptureSession(camera, targets, cameraHandler)
        }

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
//                addTarget(activitySharedCameraBiding.glsurfaceview)
            }

        // This will keep sending the capture request as frequently as possible until the session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)



        // Store the ARCore shared camera reference.
        sharedCamera = sharedSession!!.sharedCamera

        // Store the ID of the camera used by ARCore.
        cameraId = sharedSession!!.cameraConfig.cameraId

        // Use the currently configured CPU image size.
        val desiredCpuImageSize = sharedSession!!.cameraConfig.imageSize
        cpuImageReader = ImageReader.newInstance(
            desiredCpuImageSize.width,
            desiredCpuImageSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        cpuImageReader!!.setOnImageAvailableListener(this, backgroundHandler)

        // When ARCore is running, make sure it also updates our CPU image surface.
        sharedCamera!!.setAppSurfaces(
            cameraId, Arrays.asList(
                cpuImageReader!!.surface
            )
        )
        try {

            // Wrap our callback in a shared camera callback.
            val wrappedCallback =
                sharedCamera!!.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler)

            // Store a reference to the camera system service.
//            cameraManager = this.getSystemService(CAMERA_SERVICE) as CameraManager

            // Get the characteristics for the ARCore camera.
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)

            // On Android P and later, get list of keys that are difficult to apply per-frame and can
            // result in unexpected delays when modified during the capture session lifetime.
            if (Build.VERSION.SDK_INT >= 28) {
                keysThatCanCauseCaptureDelaysWhenModified = characteristics.availableSessionKeys
                if (keysThatCanCauseCaptureDelaysWhenModified == null) {
                    // Initialize the list to an empty list if getAvailableSessionKeys() returns null.
                    keysThatCanCauseCaptureDelaysWhenModified = ArrayList()
                }
            }

            // Prevent app crashes due to quick operations on camera open / close by waiting for the
            // capture session's onActive() callback to be triggered.
            captureSessionChangesPossible = false

            // Open the camera device using the ARCore wrapped callback.
            cameraManager.openCamera(cameraId!!, wrappedCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun <T> checkIfKeyCanCauseDelay(key: CaptureRequest.Key<T>): Boolean {
        return if (Build.VERSION.SDK_INT >= 28) {
            // On Android P and later, return true if key is difficult to apply per-frame.
            keysThatCanCauseCaptureDelaysWhenModified!!.contains(key)
        } else {
            // On earlier Android versions, log a warning since there is no API to determine whether
            // the key is difficult to apply per-frame. Certain keys such as CONTROL_AE_TARGET_FPS_RANGE
            // are known to cause a noticeable delay on certain devices.
            // If avoiding unexpected capture delays when switching between non-AR and AR modes is
            // important, verify the runtime behavior on each pre-Android P device on which the app will
            // be distributed. Note that this device-specific runtime behavior may change when the
            // device's operating system is updated.
            Log.w(
                TAG,
                "Changing "
                        + key
                        + " may cause a noticeable capture delay. Please verify actual runtime behavior on"
                        + " specific pre-Android P devices that this app will be distributed on."
            )
            // Allow the change since we're unable to determine whether it can cause unexpected delays.
            false
        }
    }

    // If possible, apply effect in non-AR mode, to help visually distinguish between from AR mode.
    private fun setCameraEffects(captureBuilder: CaptureRequest.Builder?) {
        if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
            Log.w(
                TAG,
                "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions."
            )
        } else {
            Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.")
            captureBuilder!!.set(
                CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA
            )
        }
    }

    // Close the camera device.
    private fun closeCamera() {
        if (captureSession != null) {
            captureSession!!.close()
            captureSession = null
        }
        if (cameraDevice != null) {
            waitUntilCameraCaptureSessionIsActive()
            safeToExitApp.close()
            cameraDevice!!.close()
            safeToExitApp.block()
        }
        if (cpuImageReader != null) {
            cpuImageReader!!.close()
            cpuImageReader = null
        }
    }

    // Surface texture on frame available callback, used only in non-AR mode.
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        // Log.d(TAG, "onFrameAvailable()");
    }

    // CPU image reader callback.
    override fun onImageAvailable(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "onImageAvailable: Skipping null image.")
            return
        }
        image.close()
        cpuImagesProcessed++

        // Reduce the screen update to once every two seconds with 30fps if running as automated test.
        if (!automatorRun.get() || automatorRun.get() && cpuImagesProcessed % 60 == 0) {
            runOnUiThread {
                statusTextView!!.text = """CPU images processed: $cpuImagesProcessed

Mode: ${if (arMode) "AR" else "non-AR"} 
ARCore active: $arcoreActive 
Should update surface texture: ${shouldUpdateSurfaceTexture.get()}"""
            }
        }
    }

    // Android permission request callback.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!hasCameraPermission(this)) {
            Toast.makeText(
                applicationContext,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                launchPermissionSettings(this)
            }
            finish()
        }
    }

    // Android focus change callback.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // GL surface created callback. Will be called on the GL thread.
    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        surfaceCreated = true
        openCamera()
        // Set GL clear color to black.
        GLES31.glClearColor(0f, 0f, 0f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the camera preview image texture. Used in non-AR and AR mode.
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread(
                this, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)

        } catch (e: IOException) {
            Log.e(TAG, "Failed to read an asset file", e)
        }
    }

    // GL surface changed callback. Will be called on the GL thread.
    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES31.glViewport(0, 0, width, height)
        displayRotationHelper!!.onSurfaceChanged(width, height)
        runOnUiThread {
            // Adjust layout based on display orientation.
            imageTextLinearLayout!!.orientation =
                if (width > height) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
    }

    // GL draw callback. Will be called each frame on the GL thread.
    override fun onDrawFrame(gl: GL10) {
        // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT or GLES31.GL_DEPTH_BUFFER_BIT)
        if (!shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return
        }

        // Handle display rotations.
        displayRotationHelper!!.updateSessionIfNeeded(sharedSession)
        try {
            if (arMode) {
                onDrawFrameARCore()
            } else {
                onDrawFrameCamera2()
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    // Draw frame when in non-AR mode. Called on the GL thread.
    fun onDrawFrameCamera2() {
        val texture = sharedCamera!!.surfaceTexture

        // ARCore may attach the SurfaceTexture to a different texture from the camera texture, so we
        // need to manually reattach it to our desired texture.
        if (isFirstFrameWithoutArcore.getAndSet(false)) {
            try {
                texture.detachFromGLContext()
            } catch (e: RuntimeException) {
                // Ignore if fails, it may not be attached yet.
            }
            texture.attachToGLContext(backgroundRenderer.textureId)
        }

        // Update the surface.
        texture.updateTexImage()

        // Account for any difference between camera sensor orientation and display orientation.
        val rotationDegrees = displayRotationHelper!!.getCameraSensorToDisplayRotation(cameraId)

        // Determine size of the camera preview image.
        val size = sharedSession!!.cameraConfig.textureSize

        // Determine aspect ratio of the output GL surface, accounting for the current display rotation
        // relative to the camera sensor orientation of the device.
        val displayAspectRatio =
            displayRotationHelper!!.getCameraSensorRelativeViewportAspectRatio(cameraId)

        // Render camera preview image to the GL surface.
        backgroundRenderer.draw(size.width, size.height, displayAspectRatio, rotationDegrees)
    }

    // Draw frame when in AR mode. Called on the GL thread.
    @Throws(CameraNotAvailableException::class)
    fun onDrawFrameARCore() {
        if (!arcoreActive) {
            // ARCore not yet active, so nothing to draw yet.
            return
        }
        if (errorCreatingSession) {
            // Session not created, so nothing to draw.
            return
        }

        // Perform ARCore per-frame update.
        val frame = sharedSession!!.update()
        val camera = frame.camera

        // Handle screen tap.
        handleTap(frame, camera)

        // If frame is ready, render camera preview image to the GL surface.
        backgroundRenderer.draw(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // Get projection matrix.
        val projmtx = FloatArray(16)
        camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

        // Get camera matrix and draw.
        val viewmtx = FloatArray(16)
        camera.getViewMatrix(viewmtx, 0)

        // Compute lighting from average intensity of the image.
        // The first three components are color scaling factors.
        // The last one is the average pixel intensity in gamma space.
        val colorCorrectionRgba = FloatArray(4)
        frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
        frame.acquirePointCloud().use { pointCloud ->
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewmtx, projmtx)
        }

        // If we detected any plane and snackbar is visible, then hide the snackbar.
        if (messageSnackbarHelper.isShowing) {
            for (plane in sharedSession!!.getAllTrackables(
                Plane::class.java
            )) {
                if (plane.trackingState == TrackingState.TRACKING) {
                    messageSnackbarHelper.hide(this)
                    break
                }
            }
        }

        // Visualize planes.
        planeRenderer.drawPlanes(
            sharedSession!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx
        )

        // Visualize anchors created by touch.
        val scaleFactor = 1.0f
        for (coloredAnchor in anchors) {
            if (coloredAnchor.anchor.trackingState != TrackingState.TRACKING) {
                continue
            }
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to sharedSession.update() as ARCore refines its estimate of the world.
            coloredAnchor.anchor.pose.toMatrix(anchorMatrix, 0)

            // Update and draw the model and its shadow.
            virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
            virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
            virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
            virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
        }
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper!!.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            for (hit in frame.hitTest(tap)) {
                // Check if any plane was hit, and if it was hit inside the plane polygon
                val trackable = hit.trackable
                // Creates an anchor if a plane or an oriented point was hit.
                if ((trackable is Plane
                            && trackable.isPoseInPolygon(hit.hitPose)
                            && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                    || (trackable is Point
                            && trackable.orientationMode
                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
                ) {
                    // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size >= 20) {
                        anchors[0].anchor.detach()
                        anchors.removeAt(0)
                    }

                    // Assign a color to the object for rendering based on the trackable type
                    // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
                    // for AR_TRACKABLE_PLANE, it's green color.
                    val objColor: FloatArray
                    objColor = if (trackable is Point) {
                        floatArrayOf(66.0f, 133.0f, 244.0f, 255.0f)
                    } else if (trackable is Plane) {
                        floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
                    } else {
                        DEFAULT_COLOR
                    }

                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.
                    anchors.add(ColoredAnchor(hit.createAnchor(), objColor))
                    break
                }
            }
        }
    }/*userRequestedInstall=*/// Request ARCore installation or update if needed.

    // Make sure ARCore is installed and supported on this device.
    private val isARCoreSupportedAndUpToDate: Boolean
        private get() {
            // Make sure ARCore is installed and supported on this device.
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            when (availability) {
                Availability.SUPPORTED_INSTALLED -> {}
                Availability.SUPPORTED_APK_TOO_OLD, Availability.SUPPORTED_NOT_INSTALLED -> try {
                    // Request ARCore installation or update if needed.
                    val installStatus =
                        ArCoreApk.getInstance().requestInstall(this,  /*userRequestedInstall=*/true)
                    when (installStatus) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            Log.e(TAG, "ARCore installation requested.")
                            return false
                        }
                        ArCoreApk.InstallStatus.INSTALLED -> {}
                    }
                } catch (e: UnavailableException) {
                    Log.e(TAG, "ARCore not installed", e)
                    runOnUiThread {
                        Toast.makeText(
                            applicationContext,
                            "ARCore not installed\n$e",
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    finish()
                    return false
                }
                Availability.UNKNOWN_ERROR, Availability.UNKNOWN_CHECKING, Availability.UNKNOWN_TIMED_OUT, Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Log.e(
                        TAG,
                        "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned "
                                + availability
                    )
                    runOnUiThread {
                        Toast.makeText(
                            applicationContext,
                            "ARCore is not supported on this device, "
                                    + "ArCoreApk.checkAvailability() returned "
                                    + availability,
                            Toast.LENGTH_LONG
                        )
                            .show()
                    }
                    return false
                }
            }
            return true
        }

    companion object {
        private val TAG = SharedCameraActivity::class.java.simpleName
        private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

        // Required for test run.
        private const val AUTOMATOR_DEFAULT: Short = 0
        private const val AUTOMATOR_KEY = "automator"

        /** Combination of all flags required to put activity into immersive mode */
        const val FLAGS_FULLSCREEN =
            View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }

    }
}