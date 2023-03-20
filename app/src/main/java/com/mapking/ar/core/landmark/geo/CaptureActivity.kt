package com.mapking.ar.core.landmark.geo

import android.Manifest.permission
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.mapking.ar.core.landmark.geo.common.helpers.*
import com.mapking.ar.core.landmark.geo.common.rendering.BackgroundRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.ObjectRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.PlaneRenderer
import com.mapking.ar.core.landmark.geo.common.rendering.PointCloudRenderer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class CaptureActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        loadInternalStateFromIntentExtras()

        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up touch listener.

        // Set up touch listener.
        tapHelper = TapHelper( /*context=*/this)
        surfaceView!!.setOnTouchListener(tapHelper)

        // Set up renderer.

        // Set up renderer.
        surfaceView!!.preserveEGLContextOnPause = true
        surfaceView!!.setEGLContextClientVersion(2)
        surfaceView!!.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.

        surfaceView!!.setRenderer(this)
        surfaceView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView!!.setWillNotDraw(false)

        installRequested = false

        recordingPlaybackPathTextView = findViewById(R.id.recording_playback_path)
        startRecordingButton = findViewById(R.id.start_recording_button)
        stopRecordingButton = findViewById(R.id.stop_recording_button)
        startPlaybackButton = findViewById(R.id.playback_button)
        stopPlaybackButton = findViewById(R.id.close_playback_button)
        startRecordingButton!!.setOnClickListener(View.OnClickListener { view: View? -> startRecording() })
        stopRecordingButton!!.setOnClickListener(View.OnClickListener { view: View? -> stopRecording() })
        startPlaybackButton!!.setOnClickListener(View.OnClickListener { view: View? -> startPlayback() })
        stopPlaybackButton!!.setOnClickListener(View.OnClickListener { view: View? -> stopPlayback() })
        updateUI()
    }

    private val TAG: String =
        CaptureActivity::class.java.getSimpleName()

    // MP4 dataset naming convention: arcore-dataset-YYYY-MM-DD-hh-mm-ss.mp4
    private val MP4_DATASET_FILENAME_TEMPLATE = "arcore-dataset-%s.mp4"
    private val MP4_DATASET_TIMESTAMP_FORMAT = "yyyy-MM-dd-HH-mm-ss"

    // Keys to keep track of the active dataset and playback state between restarts.
    private val DESIRED_DATASET_PATH_KEY = "desired_dataset_path_key"
    private val DESIRED_APP_STATE_KEY = "desired_app_state_key"
    private val PERMISSIONS_REQUEST_CODE = 0

    // Recording and playback requires android.permission.WRITE_EXTERNAL_STORAGE and
    // android.permission.CAMERA to operate. These permissions must be mirrored in the manifest.
    private val requiredPermissions =
        Arrays.asList(permission.CAMERA, permission.WRITE_EXTERNAL_STORAGE)

    // Randomly generated UUID and custom MIME type to mark the anchor track for this sample.
    private val ANCHOR_TRACK_ID = UUID.fromString("a65e59fc-2e13-4607-b514-35302121c138")
    private val ANCHOR_TRACK_MIME_TYPE = "application/hello-recording-playback-anchor"

    // The app state so that it can be preserved when the activity restarts. This is also used to
    // update the UI.
    private val currentState = AtomicReference(AppState.IDLE)

    private var playbackDatasetPath: String? = null
    private var lastRecordingDatasetPath: String? = null
    private var startRecordingButton: Button? = null
    private var stopRecordingButton: Button? = null
    private var startPlaybackButton: Button? = null
    private var stopPlaybackButton: Button? = null
    private var recordingPlaybackPathTextView: TextView? = null

    private var session: Session? = null
    private val messageSnackbarHelper: SnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper: TrackingStateHelper = TrackingStateHelper(this)
    private var tapHelper: TapHelper? = null
    private var surfaceView: GLSurfaceView? = null

    // The Renderers are created here, and initialized when the GL surface is created.
    private val backgroundRenderer: BackgroundRenderer = BackgroundRenderer()
    private val virtualObject: ObjectRenderer = ObjectRenderer()
    private val virtualObjectShadow: ObjectRenderer = ObjectRenderer()
    private val planeRenderer: PlaneRenderer = PlaneRenderer()
    private val pointCloudRenderer: PointCloudRenderer = PointCloudRenderer()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val DEFAULT_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

    private val SEARCHING_PLANE_MESSAGE = "Searching for surfaces..."

    // Anchors created from taps used for object placing with a given color.
    private class ColoredAnchor(val anchor: Anchor, val color: FloatArray)

    private val anchors = ArrayList<ColoredAnchor>()
    private val anchorsToBeRecorded = ArrayList<ColoredAnchor>()

    private var installRequested = false

    enum class AppState {
        IDLE, RECORDING, PLAYBACK
    }


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread( /*context=*/this)
            planeRenderer.createOnGlThread( /*context=*/this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread( /*context=*/this)
            virtualObject.createOnGlThread( /*context=*/this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            virtualObjectShadow.createOnGlThread( /*context=*/
                this, "models/andy_shadow.obj", "models/andy_shadow.png"
            )
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (e: IOException) {
            Log.e(
                TAG,
                "Failed to read an asset file",
                e
            )
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        TODO("Not yet implemented")
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to tell driver it should not load any pixels from previous frame.

        // Clear screen to tell driver it should not load any pixels from previous frame.
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT or GLES31.GL_DEPTH_BUFFER_BIT)

        // Do not render anything or call session methods until session is created.

        // Do not render anything or call session methods until session is created.
        if (session == null) {
            return
        }

        // Notify ARCore session that the view size changed so that the projection matrix and
        // the video background can be properly adjusted.

        // Notify ARCore session that the view size changed so that the projection matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera

            // Handle one tap per frame.
            val anchor: ColoredAnchor? = handleTap(frame, camera)
            if (anchor != null) {
                // If we created an anchor, then try to record it.
                anchorsToBeRecorded.add(anchor)
            }

            // Try to record any anchors that have not been recorded yet.
            recordAnchors(session!!, frame, camera)

            // If we are playing back, then add any recorded anchors to the session.
            addRecordedAnchors(session!!, frame, camera)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3D objects, show tracking failure reason instead.
            if (camera.trackingState == TrackingState.PAUSED) {
                messageSnackbarHelper.showMessage(
                    this, TrackingStateHelper.getTrackingFailureReasonString(camera)
                )
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

            // No tracking failure at this point. If we detected any planes, then hide the
            // message UI. If not planes detected, show searching planes message.
            if (hasTrackingPlane()) {
                messageSnackbarHelper.hide(this)
            } else {
                messageSnackbarHelper.showMessage(
                    this,
                    SEARCHING_PLANE_MESSAGE
                )
            }

            // Visualize detected planes.
            planeRenderer.drawPlanes(
                session!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projmtx
            )

            // Visualize anchors created by tapping.
            val scaleFactor = 1.0f
            for (coloredAnchor in anchors) {
                if (coloredAnchor.anchor.trackingState != TrackingState.TRACKING) {
                    continue
                }
                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to session.update() as ARCore refines its estimate of the world.
                coloredAnchor.anchor.pose.toMatrix(anchorMatrix, 0)

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
                virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color)
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(
                TAG,
                "Exception on the OpenGL thread",
                t
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                // If we did not yet obtain runtime permission on Android M and above, now is a good time to
                // ask the user for it.
                if (requestPermissions()) {
                    return
                }

                // Create the session.
                session = Session( /* context= */this)
                if (currentState.get() == AppState.PLAYBACK) {
                    // Dataset playback will start when session.resume() is called.
                    setPlaybackDatasetPath()
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install Google Play Services for AR (ARCore)"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install Google Play Services for AR (ARCore)"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update Google Play Services for AR (ARCore)"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, "$message $exception")
                Log.e(
                    TAG,
                    "Exception creating session",
                    exception
                )
                return
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            // Playback will now start if an MP4 dataset has been set.
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }

        if (currentState.get() == AppState.PLAYBACK) {
            // Must be called after dataset playback is started by call to session.resume().
            checkPlaybackStatus()
        }
        surfaceView!!.onResume()
        displayRotationHelper!!.onResume()
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView!!.onPause()
            if (currentState.get() == AppState.RECORDING) {
                stopRecording()
            }
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            for (i in grantResults.indices) {
                if (grantResults.get(i) != PackageManager.PERMISSION_GRANTED) {
                    logAndShowErrorMessage("Cannot start app, missing permission: " + permissions[i])
                    finish()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    /** Try to create an anchor if the user has tapped the screen.  */
    private fun handleTap(frame: Frame, camera: Camera): ColoredAnchor? {
        // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
        val tap = tapHelper!!.poll()
        if (tap != null && camera.trackingState == TrackingState.TRACKING) {
            if (session != null) {
                val earth = session!!.earth!!
                val qx = 0f
                val qy =
                    sin((Math.PI - Math.toRadians(earth.cameraGeospatialPose.heading)) / 2).toFloat()
                val qz = 0f
                val qw =
                    cos((Math.PI - Math.toRadians(earth.cameraGeospatialPose.heading)) / 2).toFloat()

                earth.createAnchor(
                    earth.cameraGeospatialPose.latitude,
                    earth.cameraGeospatialPose.longitude,
                    earth.cameraGeospatialPose.altitude - 1.5,
                    qx, qy, qz, qw
                    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.

                )
            }
//            for (hit in frame.hitTest(tap)) {
//                // Check if any plane was hit, and if it was hit inside the plane polygon.
//                val trackable = hit.trackable
//                // Creates an anchor if a plane or an oriented point was hit.
//                if ((trackable is Plane
//                            && trackable.isPoseInPolygon(hit.hitPose)
//                            && PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
//                    || (trackable is Point
//                            && trackable.orientationMode
//                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
//                ) {
//                    // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
//                    // Cap the number of objects created. This avoids overloading both the
//                    // rendering system and ARCore.
//                    if (anchors.size >= 20) {
//                        anchors[0].anchor.detach()
//                        anchors.removeAt(0)
//                    }
//
//                    // Assign a color to the object for rendering based on the trackable type
//                    // this anchor attached to.
//                    val objColor: FloatArray
//                    objColor = if (trackable is Point) {
//                        floatArrayOf(66.0f, 133.0f, 244.0f, 255.0f) // Blue.
//                    } else if (trackable is Plane) {
//                        floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f) // Green.
//                    } else {
//                        DEFAULT_COLOR
//                    }
//                    val anchor = ColoredAnchor(hit.createAnchor(), objColor)
//                    // Adding an Anchor tells ARCore that it should track this position in
//                    // space. This anchor is created on the Plane to place the 3D model
//                    // in the correct position relative both to the world and to the plane.
//                    anchors.add(anchor)
//                    return anchor
//                }
//            }
        }
        return null
    }

    /**
     * Try to add anchors to an MP4 data track track if the app is currently recording.
     *
     *
     * Track data recording can sometimes fail due an image not being available for recording in
     * ARCore, so we try to record all anchors that have not been recorded yet.
     */
    private fun recordAnchors(session: Session, frame: Frame, camera: Camera) {
        if (session.recordingStatus != RecordingStatus.OK) {
            // We do not record anchors created before we started recording.
            anchorsToBeRecorded.clear()
            return
        }
        val anchorIterator = anchorsToBeRecorded.iterator()
        while (anchorIterator.hasNext()) {
            val anchor = anchorIterator.next()
            // Transform the anchor pose world coordinates in to camera coordinate frame for easy
            // placement during playback.
            val pose = camera.pose.inverse().compose(anchor.anchor.pose)
            val translation = pose.translation
            val quaternion = pose.rotationQuaternion
            val payload =
                ByteBuffer.allocate(4 * (translation.size + quaternion.size + anchor.color.size))
            val floatView = payload.asFloatBuffer()
            floatView.put(translation)
            floatView.put(quaternion)
            floatView.put(anchor.color)
            try {
                frame.recordTrackData(
                    ANCHOR_TRACK_ID,
                    payload
                )
                anchorIterator.remove()
            } catch (e: IllegalStateException) {
                Log.e(
                    TAG,
                    "Could not record anchor into external data track.",
                    e
                )
                return
            }
        }
    }

    /** During playback, recreate any anchors that were placed during recording.  */
    private fun addRecordedAnchors(session: Session, frame: Frame, camera: Camera) {
        for (data in frame.getUpdatedTrackData(ANCHOR_TRACK_ID)) {
            val payload = data.data
            val translation = FloatArray(3)
            val quaternion = FloatArray(4)
            val color = FloatArray(4)
            val floatView = payload.asFloatBuffer()
            floatView[translation]
            floatView[quaternion]
            floatView[color]

            // Transform the recorded anchor pose in the camera coordinate frame back into world
            // coordinates.
            val pose = camera.pose.compose(Pose(translation, quaternion))
            val anchor = ColoredAnchor(session.createAnchor(pose), color)
            anchors.add(anchor)
        }
    }

    /** Checks if we detected at least one plane.  */
    private fun hasTrackingPlane(): Boolean {
        for (plane in session!!.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    /**
     * Requests any not (yet) granted required permissions needed for recording and playback.
     *
     *
     * Returns false if all permissions are already granted. Otherwise, requests missing
     * permissions and returns true.
     */
    private fun requestPermissions(): Boolean {
        val permissionsNotGranted: MutableList<String> = ArrayList()
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNotGranted.add(permission)
            }
        }
        if (permissionsNotGranted.isEmpty()) {
            return false
        }
        ActivityCompat.requestPermissions(
            this,
            permissionsNotGranted.toTypedArray(),
            PERMISSIONS_REQUEST_CODE
        )
        return true
    }

    /** Sets the path of the MP4 dataset to playback.  */
    private fun setPlaybackDatasetPath() {
        if (session!!.playbackStatus == PlaybackStatus.OK) {
            logAndShowErrorMessage("Session is already playing back.")
            setStateAndUpdateUI(AppState.PLAYBACK)
            return
        }
        if (playbackDatasetPath != null) {
            try {
                session!!.setPlaybackDatasetUri(Uri.fromFile(File(playbackDatasetPath)))
            } catch (e: PlaybackFailedException) {
                val errorMsg = "Failed to set playback MP4 dataset. $e"
                Log.e(
                    TAG,
                    errorMsg,
                    e
                )
                messageSnackbarHelper.showError(this, errorMsg)
                Log.d(
                    TAG,
                    "Setting app state to IDLE, as the playback is not in progress."
                )
                setStateAndUpdateUI(AppState.IDLE)
                return
            }
            setStateAndUpdateUI(AppState.PLAYBACK)
        }
    }

    /** Generates a new MP4 dataset filename based on the current system time.  */
    private fun getNewMp4DatasetFilename(): String? {
//        return java.lang.String.format(
//            Locale.ENGLISH,
//            MP4_DATASET_FILENAME_TEMPLATE,
//            Date.now()
//                .toString(MP4_DATASET_TIMESTAMP_FORMAT)
//        )
        return java.lang.String.format(
            Locale.ENGLISH,
            MP4_DATASET_FILENAME_TEMPLATE,
        )
    }

    /** Generates a new MP4 dataset path based on the current system time.  */
    private fun getNewDatasetPath(): String? {
        val baseDir = getExternalFilesDir(null) ?: return null
        return File(getExternalFilesDir(null), getNewMp4DatasetFilename()).absolutePath
    }

    /** Updates UI behaviors based on current app state.  */
    private fun updateUI() {
        when (currentState.get()) {
            AppState.IDLE -> {
                startRecordingButton!!.visibility = View.VISIBLE
                startRecordingButton!!.isEnabled = true
                stopRecordingButton!!.visibility = View.GONE
                stopRecordingButton!!.isEnabled = false
                stopPlaybackButton!!.visibility = View.INVISIBLE
                stopPlaybackButton!!.isEnabled = false
                startPlaybackButton!!.isEnabled = playbackDatasetPath != null
                recordingPlaybackPathTextView!!.text = resources
                    .getString(
                        R.string.playback_path_text,
                        if (playbackDatasetPath == null) "" else playbackDatasetPath
                    )
            }
            AppState.RECORDING -> {
                startRecordingButton!!.visibility = View.GONE
                startRecordingButton!!.isEnabled = false
                stopRecordingButton!!.visibility = View.VISIBLE
                stopRecordingButton!!.isEnabled = true
                stopPlaybackButton!!.visibility = View.INVISIBLE
                stopPlaybackButton!!.isEnabled = false
                startPlaybackButton!!.isEnabled = false
                recordingPlaybackPathTextView!!.text = resources
                    .getString(
                        R.string.recording_path_text,
                        if (lastRecordingDatasetPath == null) "" else lastRecordingDatasetPath
                    )
            }
            AppState.PLAYBACK -> {
                startRecordingButton!!.visibility = View.INVISIBLE
                stopRecordingButton!!.visibility = View.INVISIBLE
                startPlaybackButton!!.visibility = View.INVISIBLE
                startRecordingButton!!.isEnabled = false
                stopRecordingButton!!.isEnabled = false
                stopPlaybackButton!!.visibility = View.VISIBLE
                stopPlaybackButton!!.isEnabled = true
                recordingPlaybackPathTextView!!.text = ""
            }
        }
    }

    /** Performs action when start_recording button is clicked.  */
    private fun startRecording() {
        try {
            lastRecordingDatasetPath = getNewDatasetPath()
            if (lastRecordingDatasetPath == null) {
                logAndShowErrorMessage("Failed to generate a MP4 dataset path for recording.")
                return
            }
            val anchorTrack =
                Track(session).setId(ANCHOR_TRACK_ID)
                    .setMimeType(ANCHOR_TRACK_MIME_TYPE)
            session!!.startRecording(
                RecordingConfig(session)
                    .setMp4DatasetUri(Uri.fromFile(File(lastRecordingDatasetPath)))
                    .setAutoStopOnPause(false)
                    .addTrack(anchorTrack)
            )
        } catch (e: RecordingFailedException) {
            val errorMessage = "Failed to start recording. $e"
            Log.e(
                TAG,
                errorMessage,
                e
            )
            messageSnackbarHelper.showError(this, errorMessage)
            return
        }
        if (session!!.recordingStatus != RecordingStatus.OK) {
            logAndShowErrorMessage(
                "Failed to start recording, recording status is " + session!!.recordingStatus
            )
            return
        }
        setStateAndUpdateUI(AppState.RECORDING)
    }

    /** Performs action when stop_recording button is clicked.  */
    private fun stopRecording() {
        try {
            session!!.stopRecording()
        } catch (e: RecordingFailedException) {
            val errorMessage = "Failed to stop recording. $e"
            Log.e(
                TAG,
                errorMessage,
                e
            )
            messageSnackbarHelper.showError(this, errorMessage)
            return
        }
        if (session!!.recordingStatus == RecordingStatus.OK) {
            logAndShowErrorMessage(
                "Failed to stop recording, recording status is " + session!!.recordingStatus
            )
            return
        }
        if (File(lastRecordingDatasetPath).exists()) {
            playbackDatasetPath = lastRecordingDatasetPath
            Log.d(
                TAG,
                "MP4 dataset has been saved at: $playbackDatasetPath"
            )
        } else {
            logAndShowErrorMessage(
                "Recording failed. File $lastRecordingDatasetPath wasn't created."
            )
        }
        setStateAndUpdateUI(AppState.IDLE)
    }

    /** Helper function to log error message and show it on the screen.  */
    private fun logAndShowErrorMessage(errorMessage: String) {
        Log.e(
            TAG,
            errorMessage
        )
        messageSnackbarHelper.showError(this, errorMessage)
    }

    /** Helper function to set state and update UI.  */
    private fun setStateAndUpdateUI(state: AppState) {
        currentState.set(state)
        updateUI()
    }

    /** Performs action when playback button is clicked.  */
    private fun startPlayback() {
        if (playbackDatasetPath == null) {
            return
        }
        currentState.set(AppState.PLAYBACK)
        restartActivityWithIntentExtras()
    }

    /** Performs action when close_playback button is clicked.  */
    private fun stopPlayback() {
        currentState.set(AppState.IDLE)
        restartActivityWithIntentExtras()
    }

    /** Checks the playback is in progress without issues.  */
    private fun checkPlaybackStatus() {
        if (session!!.playbackStatus != PlaybackStatus.OK
            && session!!.playbackStatus != PlaybackStatus.FINISHED
        ) {
            logAndShowErrorMessage(
                "Failed to start playback, playback status is: " + session!!.playbackStatus
            )
            setStateAndUpdateUI(AppState.IDLE)
        }
    }

    /**
     * Restarts current activity to enter or exit playback mode.
     *
     *
     * This method simulates an app with separate activities for recording and playback by
     * restarting the current activity and passing in the desired app state via an intent with extras.
     */
    private fun restartActivityWithIntentExtras() {
        val intent = this.intent
        val bundle = Bundle()
        bundle.putString(
            DESIRED_APP_STATE_KEY,
            currentState.get().name
        )
        bundle.putString(
            DESIRED_DATASET_PATH_KEY,
            playbackDatasetPath
        )
        intent.putExtras(bundle)
        finish()
        this.startActivity(intent)
    }

    /** Loads desired state from intent extras, if available.  */
    private fun loadInternalStateFromIntentExtras() {
        if (intent == null || intent.extras == null) {
            return
        }
        val bundle = intent.extras
        if (bundle!!.containsKey(DESIRED_DATASET_PATH_KEY)) {
            playbackDatasetPath =
                intent.getStringExtra(DESIRED_DATASET_PATH_KEY)
        }
        if (bundle.containsKey(DESIRED_APP_STATE_KEY)) {
            val state =
                intent.getStringExtra(DESIRED_APP_STATE_KEY)
            if (state != null) {
                when (state) {
                    "PLAYBACK" -> currentState.set(AppState.PLAYBACK)
                    "IDLE" -> currentState.set(AppState.IDLE)
                    "RECORDING" -> currentState.set(AppState.RECORDING)
                    else -> {}
                }
            }
        }
    }
}