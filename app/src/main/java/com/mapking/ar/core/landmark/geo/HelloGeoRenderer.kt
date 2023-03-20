/*
 * Copyright 2022 Mapking International
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mapking.ar.core.landmark.geo

import android.hardware.camera2.*
import android.net.Uri
import android.opengl.Matrix
import android.os.Handler
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.mapking.ar.core.landmark.geo.common.helpers.DisplayRotationHelper
import com.mapking.ar.core.landmark.geo.common.helpers.TrackingStateHelper
import com.mapking.ar.core.landmark.geo.common.samplerender.*
import com.mapking.ar.core.landmark.geo.common.samplerender.arcore.BackgroundRenderer
import com.mapking.ar.core.landmark.geo.helpers.BearingUtils
import com.opencsv.CSVReader
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


class HelloGeoRenderer(val fragment: MainFragment) :
    SampleRender.Renderer, DefaultLifecycleObserver {
    //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
    companion object {
        val TAG = "HelloGeoRenderer"

        private val Z_NEAR = 0.1f
        private val Z_FAR = 1000f
    }

    var defaultAltitude = 1.5f
    var configAltitude = defaultAltitude
    var configModelScaleY = 1f;
    var configModelScaleZ = 1f;

    val configControlStep = 0.00001f
    val configControlDefault = 0f
    var configControlX = configControlDefault
    var configControlY = configControlDefault

    lateinit var backgroundRenderer: BackgroundRenderer
    lateinit var virtualSceneFramebuffer: Framebuffer
    var hasSetTextureNames = false

    lateinit var redLineVirtualObject: VirtualObject
    lateinit var greenLineVirtualObject: VirtualObject
    lateinit var blueLineVirtualObject: VirtualObject

    lateinit var virtualObjects: ArrayList<VirtualObject>

    // Virtual object (ARCore pawn)
    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader
    lateinit var virtualObjectTexture: Texture
    lateinit var sampleRender: SampleRender

    // Virtual object (DEMO model)
    lateinit var virtualDemoModelObjectMesh: Mesh
    lateinit var virtualDemoModelObjectShader: Shader
    lateinit var virtualDemoModelObjectTexture: Texture

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    val modelMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val modelViewMatrix = FloatArray(16) // view x model

    // The thresholds that are required for horizontal and heading accuracies before entering into the
    // LOCALIZED state. Once the accuracies are equal or less than these values, the app will
    // allow the user to place anchors.
    private val LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10.0
    private val LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES = 15.0

    // Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
    // revert back to the LOCALIZING state.
    private val LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10.0
    private val LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES = 10.0

    val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    //    var options = PolylineOptions()
    var polylineOptions: ArrayList<PolylineOptions> = arrayListOf()
    var polyline: ArrayList<Polyline> = arrayListOf()

    val session
        get() = fragment.arCoreSessionHelper.session


    val displayRotationHelper = DisplayRotationHelper(fragment.requireActivity())
    val trackingStateHelper = TrackingStateHelper(fragment.requireActivity())

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    private fun createRedLineVO(render: SampleRender): VirtualObject {
        val texture =
            Texture.createFromAsset(
                render,
                "models/red_square.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
        val mesh = Mesh.createFromAsset(
            render,
            "models/0.5m_square.obj"
        );
        val shader =
            Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",
                /*defines=*/ null
            )
                .setTexture("u_Texture", texture)

        return VirtualObject(mesh, shader, texture)
    }

    private fun createBlueLineVO(render: SampleRender): VirtualObject {
        val texture =
            Texture.createFromAsset(
                render,
                "models/blue_square.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
        val mesh = Mesh.createFromAsset(
            render,
            "models/0.5m_square.obj"
        );
        val shader =
            Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",
                /*defines=*/ null
            )
                .setTexture("u_Texture", texture)

        return VirtualObject(mesh, shader, texture)

    }

    private fun createGreenLineVO(render: SampleRender): VirtualObject {
        val texture =
            Texture.createFromAsset(
                render,
                "models/green_square.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
        val mesh = Mesh.createFromAsset(
            render,
            "models/0.5m_square.obj"
        );
        val shader =
            Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",
                /*defines=*/ null
            )
                .setTexture("u_Texture", texture)

        return VirtualObject(mesh, shader, texture)
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects.
        // This involves reading shaders and 3D model files, so may throw an IOException.
        try {
            sampleRender = render
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

            blueLineVirtualObject = createBlueLineVO(render)
            redLineVirtualObject = createRedLineVO(render)
            greenLineVirtualObject = createGreenLineVO(render)
            virtualObjects = ArrayList()
            virtualObjects.add(blueLineVirtualObject)
            virtualObjects.add(greenLineVirtualObject)
            virtualObjects.add(redLineVirtualObject)

            // Virtual object to render (Geospatial Marker)
            virtualObjectTexture =
                Texture.createFromAsset(
                    render,
                    "models/red_square.png",
//                    "models/spatial_marker_house.jpg",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )


            virtualObjectMesh = Mesh.createFromAsset(
                render,
                "models/0.5m_square.obj"
//                "models/house.obj"
            );
            virtualObjectShader =
                Shader.createFromAssets(
                    render,
                    "shaders/ar_unlit_object.vert",
                    "shaders/ar_unlit_object.frag",
                    /*defines=*/ null
                )
                    .setTexture("u_Texture", virtualObjectTexture)

            // Virtual object to render (Geospatial Marker)
            virtualDemoModelObjectTexture =
                Texture.createFromAsset(
                    render,
                    "models/spatial_marker_house.jpg",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            virtualDemoModelObjectMesh = Mesh.createFromAsset(render, "models/house.obj");

            virtualDemoModelObjectShader =
                Shader.createFromAssets(
                    render,
                    "shaders/ar_unlit_object.vert",
                    "shaders/ar_unlit_object.frag",
                    /*defines=*/ null
                )
                    .setTexture("u_Texture", virtualDemoModelObjectTexture)


            backgroundRenderer.setUseDepthVisualization(render, false)
            backgroundRenderer.setUseOcclusion(render, false)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    fun drawDemoModel() {
        if (demoModelsWithConfig.isNotEmpty()) {
            demoModelsWithConfig.forEach {
                it.value.detach()
            }
            demoModelsWithConfig = mutableMapOf()
            return
        }
        addLineModel(22.321657195, 114.20883074, 354.193f, 22.591f)
    }

    private fun redrawArModel() {
        if (demoModel != null) {
            demoModel!!.anchor.detach()
            drawDemoModel()
        }
    }

    fun redrawAll() {
//        redrawArModel()
        val earth = session?.earth ?: return
        var copy: MutableMap<AnchorConfig, Anchor> = mutableMapOf()
        copy.putAll(earthAnchorsWithConfig)
        clearAllAnchorsWithConfig()
        copy.forEach {
            val _key = it.key
            addLineModel(
                AnchorConfig(
                    _key.l1,
                    _key.l2,
                    earth.cameraGeospatialPose.altitude - configAltitude,
                    _key.qx,
                    _key.qy,
                    _key.qz,
                    _key.qw,
                    _key.heading,
                    _key.scaleX,
                    configModelScaleY,
                    configModelScaleZ
                )
            )
        }
    }

    fun drawSingleModel(uri: Uri) {

    }

    private fun stylePolygon(polygon: Polygon) {
        var type = ""
        // Get the data object stored with the polygon.
        if (polygon.tag != null) {
            type = polygon.tag.toString()
        }
        var pattern: List<PatternItem?>? = null
        var strokeColor: Int = MainFragment.COLOR_BLACK_ARGB
        var fillColor: Int = MainFragment.COLOR_WHITE_ARGB
        when (type) {
            "alpha" -> {
                // Apply a stroke pattern to render a dashed line, and define colors.
                pattern = MainFragment.PATTERN_POLYGON_ALPHA
                strokeColor = MainFragment.COLOR_DARK_GREEN_ARGB
                fillColor = MainFragment.COLOR_LIGHT_GREEN_ARGB
            }
            "beta" -> {
                // Apply a stroke pattern to render a line of dots and dashes, and define colors.
                pattern = MainFragment.PATTERN_POLYGON_BETA
                strokeColor = MainFragment.COLOR_DARK_ORANGE_ARGB
                fillColor = MainFragment.COLOR_LIGHT_ORANGE_ARGB
            }
        }
        polygon.strokePattern = pattern
        polygon.strokeWidth = MainFragment.POLYGON_STROKE_WIDTH_PX.toFloat()
        polygon.strokeColor = strokeColor
        polygon.fillColor = fillColor
    }

    private fun stylePolyline(polyline: Polyline) {
        var type = ""
        // Get the data object stored with the polyline.
        if (polyline.tag != null) {
            type = polyline.tag.toString()
        }
        when (type) {
            "A" ->                 // Use a custom bitmap as the cap at the start of the line.
                polyline.startCap = CustomCap(
                    BitmapDescriptorFactory.fromResource(R.drawable.ic_arrow), 10f
                )
            "B" ->                 // Use a round cap at the start of the line.
                polyline.startCap = RoundCap()
            "C" ->                 // Use a custom bitmap as the cap at the start of the line.
                polyline.startCap = CustomCap(
                    BitmapDescriptorFactory.fromResource(R.drawable.ic_arrow), 10f
                )
        }
        polyline.endCap = RoundCap()
        polyline.width = MainFragment.POLYLINE_STROKE_WIDTH_PX.toFloat()
        polyline.color = MainFragment.COLOR_BLACK_ARGB
        polyline.jointType = JointType.ROUND
    }

    fun drawFromUri(uri: Uri) {
        val earth = session?.earth ?: return
//        Will be paused after choosing the file
//        if (earth.trackingState != TrackingState.TRACKING) {
//            return
//        }

        clearAllAnchors()
        clearAllMapPolylines()
        clearAllAnchorsWithConfig()


        updateGeospatialState(earth)
        try {
            val reader = CSVReader(
                BufferedReader(
                    InputStreamReader(
                        fragment.requireActivity().contentResolver.openInputStream(uri)
                    )
                )
            )
            var readFirstLine = false //is the first line of the csv read?
            var keys = mutableMapOf<String, Int>(
                "length" to 0,
                "bearing" to 1,
                "xa" to 2,
                "ya" to 3,
                "xb" to 4,
                "yb" to 5,
                "xc" to 6,
                "yc" to 7
            )
//            val polylines: ArrayList<LatLng> = arrayListOf()
            val iterator = reader.iterator()
            for (nextLine in iterator) {
                if (!readFirstLine) {
                    readFirstLine = true
                    nextLine.forEachIndexed { index, element ->
                        keys[element] = index
                    }
                } else {
                    Log.d(TAG, "drawFromUri: $nextLine")
                    val l2 = if (keys["xc"] != null) nextLine[keys["xc"]!!].toDouble() else 0.0
                    var lng = if (keys["xa"] != null) nextLine[keys["xa"]!!].toDouble() else 0.0
                    var lat = if (keys["ya"] != null) nextLine[keys["ya"]!!].toDouble() else 0.0
                    val polylines: ArrayList<LatLng> = arrayListOf()
                    polylines.add(LatLng(lat, lng))
                    lng = if (keys["xb"] != null) nextLine[keys["xb"]!!].toDouble() else 0.0
                    lat = if (keys["yb"] != null) nextLine[keys["yb"]!!].toDouble() else 0.0
                    polylines.add(LatLng(lat, lng))
                    val options = PolylineOptions()
                    polylines.forEach {
                        options.add(it)
                    }
                    polylineOptions.add(options)
                    //Last line so read the last value
//                    if (!iterator.hasNext()) {
//                        var lng = if (keys["xb"] != null) nextLine[keys["xb"]!!].toDouble() else 0.0
//                        var lat = if (keys["yb"] != null) nextLine[keys["yb"]!!].toDouble() else 0.0
//                        polylines.add(LatLng(lat, lng))
//                    }
//                     lng = if (keys["xb"] != null) nextLine[keys["xb"]!!].toDouble() else 0.0
//                     lat = if (keys["yb"] != null) nextLine[keys["yb"]!!].toDouble() else 0.0
//                    polylines.add(LatLng(lat,lng))


                    val l1 = if (keys["yc"] != null) nextLine[keys["yc"]!!].toDouble() else 0.0


                    val b =
                        if (keys["bearing"] != null) nextLine[keys["bearing"]!!].toFloat() else 0f
                    val l = if (keys["length"] != null) nextLine[keys["length"]!!].toFloat() else 0f
                    addLineModel(l1, l2, b, l)
                }
            }

//            polylines.forEach {
//                Log.d("DEU", "drawFromUri: ${it.latitude}, ${it.longitude}")
//                options.add(it)
//            }

            polylineOptions.forEach {
                it.points.forEach {
                    Log.d("DEU", "drawFromUri: ${it.latitude}, ${it.longitude}")
                }
                Log.d("DEU", "----------------")
                polylines.add(fragment.view.mapView?.googleMap?.addPolyline(it)!!)
            }


            fragment.view.snackbarHelper.showMessageWithDismiss(
                fragment.requireActivity(),
                "CSV Successfully loaded"
            )

        } catch (e: Exception) {
            fragment.view.snackbarHelper.showError(
                fragment.requireActivity(),
                "Sorry, something went wrong."
            )
            e.printStackTrace();
        }
    }

    private fun addDemoModel(l1: Double, l2: Double, bearing: Float, length: Float) {
        val earth = session?.earth ?: return
        val altitude = earth.cameraGeospatialPose.altitude - configAltitude
        val _heading =
            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(bearing, 90f))
        val qx = 0f
        val qy = sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        val qz = 0f
        val qw = cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        demoModelsWithConfig[AnchorConfig(
            l1, l2, altitude, qx, qy, qz, qw,
            _heading,
            1f,
            1f,
            1f
        )] =
            earth.createAnchor(
                l1,
                l2,
                altitude,
                qx,
                qy,
                qz,
                qw
            )
    }

    private fun addLineModel(l1: Double, l2: Double, bearing: Float, length: Float) {
        val earth = session?.earth ?: return
        val altitude = earth.cameraGeospatialPose.altitude - configAltitude
        val _heading = BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(bearing, 90f))
        val qx = 0f
        val qy = sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        val qz = 0f
        val qw = cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        earthAnchorsWithConfig[AnchorConfig(
            l1, l2, altitude, qx, qy, qz, qw,
            _heading,
            length * 2,
            configModelScaleY,
            configModelScaleZ
        )] =
            earth.createAnchor(
                l1 + configControlX,
                l2 + configControlY,
                altitude,
                qx,
                qy,
                qz,
                qw
            )
    }

    fun addLineModel(config: AnchorConfig) {
        val earth = session?.earth ?: return
        earthAnchorsWithConfig[config] =
            earth.createAnchor(
                config.l1 + configControlX,
                config.l2 + configControlY,
                config.altitude,
                config.qx,
                config.qy,
                config.qz,
                config.qw
            )
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
    }
    //</editor-fold>

    // latest camera
    var _camera: Camera? = null
    val camera get() = _camera!!

    // latest frame
    var _frame: Frame? = null
    val frame get() = _frame!!

    override fun onDrawFrame(render: SampleRender) {
        try {
            val session = session ?: return

            //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
            // Texture names should only be set once on a GL thread unless they change. This is done during
            // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
            // initialized during the execution of onSurfaceCreated.
            if (!hasSetTextureNames) {
                session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
                hasSetTextureNames = true
            }

            // -- Update per-frame state

            // Notify ARCore session that the view size changed so that the perspective matrix and
            // the video background can be properly adjusted.
            displayRotationHelper.updateSessionIfNeeded(session)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            _frame =
                try {
                    session.update()
                } catch (e: CameraNotAvailableException) {
                    Log.e(TAG, "Camera not available during onDrawFrame", e)
                    showError("Camera not available. Try restarting the app.")
                    return
                }

            _camera = frame.camera
            if (fragment.captureRequest.get()) {
                try {
//                    if (session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
//                        frame.acquireRawDepthImage16Bits()
//                        frame.acquireRawDepthConfidenceImage()
//                    }
//                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
//                        frame.acquireDepthImage16Bits()
//                    }
//                    frame.imageMetadata
//                    val filename = String.format(
//                        Locale.ENGLISH,
//                        "arcore_image_%s.jpeg",
//                        DateTime.now().toString("yyyy-MM-dd-HH-mm-ss")
//                    )
//                    val file = File(fragment.requireActivity().getExternalFilesDir(null), filename)
//                    val imgBytes = ImageUtils.imageToByteArray(frame.acquireCameraImage())
//                    ByteArrayInputStream(imgBytes).use { fis ->
//                        val fos = FileOutputStream(file)
//                        val buffer = ByteArray(1024)
//                        var len: Int
//                        while (fis.read(buffer).also { len = it } != -1) {
//                            fos.write(buffer, 0, len)
//                        }
//                    }
//                    val cameraManager = fragment.requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
//                    val characteristics = cameraManager.getCameraCharacteristics(session.cameraConfig.cameraId)
//                    )
//                    val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
//                        .getOutputSizes(256).maxByOrNull { it.height * it.width }!!
//
//                    val IMAGE_BUFFER_SIZE: Int = 3
//                    val imageReader =  ImageReader.newInstance(size.width, size.height, 256,
//                        IMAGE_BUFFER_SIZE)
//
//                    //Flush any images left in the image reader
//                    while (imageReader.acquireLatestImage() != null) {
//                    }
//
//                    //Start a new image queue
//                    val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
//                    imageReader.setOnImageAvailableListener({ reader ->
//                        val image = reader.acquireNextImage()
//                        Log.d(TAG, "Image available in queue: ${image.timestamp}")
//                        imageQueue.add(image)
//                    }, null)
//
//                    captureSession = sc
//                    val captureRequest = session.device.createCaptureRequest(
//                        CameraDevice.TEMPLATE_STILL_CAPTURE
//                    ).apply {
//                        addTarget(imageReader.surface)
//                    }
//                    session.capture(
//                        captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
//                            override fun onCaptureStarted(
//                                session: CameraCaptureSession,
//                                request: CaptureRequest,
//                                timestamp: Long,
//                                frameNumber: Long
//                            ) {
//                                super.onCaptureStarted(session, request, timestamp, frameNumber)
//                                activitySharedCameraBiding.glsurfaceview.post(animationTask)
//                            }
//
//                            override fun onCaptureCompleted(
//                                session: CameraCaptureSession,
//                                request: CaptureRequest,
//                                result: TotalCaptureResult
//                            ) {
//                                super.onCaptureCompleted(session, request, result)
//                                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
//                                Log.d(SharedCameraActivity.TAG, "Capture result received: $resultTimestamp")
//
//                                // Set a timeout in case image captured is dropped from the pipeline
//                                val exc = TimeoutException("Image dequeing took too long")
//                                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
//                                imageReaderHandler.postDelayed(timeoutRunnable,
//                                    SharedCameraActivity.IMAGE_CAPTURE_TIMEOUT_MILLIS
//                                )
//
//
//                                // Loop in the coroutine's context until an image with matching timestamp comes
//                                // We need to launch the coroutine context again because the callback is done in
//                                // the handler provide to the `capture` method, not in our coroutine context
//                                lifecycleScope.launch(cont.context) {
//                                    while (true) {
//
//                                        // Dequeue images while timestamps don't match
//                                        val image = imageQueue.take()
//                                        // TODO missing
//                                        // if (image.timestamp != resultTimestamp) continue
//                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && image.format != ImageFormat.DEPTH_JPEG && image.timestamp != resultTimestamp) continue
//                                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")
//
//                                        // Unset the image reader listener
//                                        imageReaderHandler.removeCallbacks(timeoutRunnable)
//                                        imageReader.setOnImageAvailableListener(null, null)
//
//                                        // Clear the queue of images, if there are left
//                                        while (imageQueue.size > 0) {
//                                            imageQueue.take().close()
//                                        }
//
//                                        //Compute EXIF orientation metadata
//                                        val rotation = relativeOrientation.value ?: 0
//                                        val mirrored =
//                                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
//                                        val exifOrientation = computeExifOrientation(rotation, mirrored)
//
//                                        // Build the result and resume progress
//                                        cont.resume(
//                                            SharedCameraActivity.Companion.CombinedCaptureResult(
//                                                image,
//                                                result,
//                                                exifOrientation,
//                                                imageReader.imageFormat
//                                            )
//                                        )
//                                        // There is not need to break out of the loop, this coroutine will suspend
//
//                                    }
//                                }
//                            }
//                        }, cameraHandler
//                    )
                } catch (e: Exception) {
                    Log.e(TAG, "onDrawFrame: ", e)
                } finally {
                    fragment.captureRequest.set(false)
                }
            }
            // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
            // used to draw the background camera image.
            backgroundRenderer.updateDisplayGeometry(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // -- Draw background
            if (frame.timestamp != 0L) {
                // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
                // drawing possible leftover data from previous sessions if the texture is reused.
                backgroundRenderer.drawBackground(render)
            }
            val earth = session.earth
            if (earth != null) {
                fragment.view.updateStatusText(earth, earth.cameraGeospatialPose)
            }
            fragment.view.updateCameraStatusText(camera)

            if (earth?.trackingState == TrackingState.TRACKING) {
                // TODO: the Earth object may be used here.
                val cameraGeospatialPose = earth.cameraGeospatialPose

                fragment.view.mapView?.updateMapPosition(
                    latitude = cameraGeospatialPose.latitude,
                    longitude = cameraGeospatialPose.longitude,
                    heading = cameraGeospatialPose.heading
                )
            }

            // If not tracking, don't draw 3D objects.
            if (camera.trackingState == TrackingState.PAUSED) {
                return
            }

            // Get projection matrix.
            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

            // Get camera matrix and draw.
            camera.getViewMatrix(viewMatrix, 0)

            render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
            //</editor-fold>

            // TODO: Obtain Geospatial information and display it on the map.





            // Draw the placed anchor, if it exists.
//        earthAnchors.forEach {
//            render.renderCompassAtAnchor(it)
//        }

            earthAnchorsWithConfig.forEach {
                render.renderCompassAtAnchor(it.value, it.key)
            }

            earthAnchors.forEach {
                render.renderCompassAtAnchor(it)
            }

            demoModelsWithConfig.forEach {
                render.renderDemoModelAtAnchor(it.value, it.key)
            }

            // Compose the virtual scene with the background.
            backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)


        } catch (e: Exception) { //handle all exception
            Log.e(TAG, "onDrawFrame: ", e)
        }
    }

    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(p0: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
//                finish()
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

    var demoModel: DemoModel? = null
    var demoModelsWithConfig: MutableMap<AnchorConfig, Anchor> = mutableMapOf()
    var arModelsWithAnchor: MutableMap<ArModel, Anchor> = mutableMapOf()
    var earthAnchors: MutableList<Anchor> = mutableListOf()
    var earthAnchorsWithConfig: MutableMap<AnchorConfig, Anchor> = mutableMapOf()
    val polylines: MutableList<Polyline> = mutableListOf()

    fun clearAllArModel() {
        val earth = session?.earth ?: return
        arModelsWithAnchor.forEach {
            it.value.detach()
        }
        arModelsWithAnchor = mutableMapOf()
    }

    fun drawArModel(arModel: ArModel) {
        val earth = session?.earth ?: return
        val altitude = earth.cameraGeospatialPose.altitude - configAltitude
        val _heading = arModel.config.heading
        earthAnchorsWithConfig[AnchorConfig(
            arModel.config.l1,
            arModel.config.l2,
            altitude,
            arModel.config.qx,
            arModel.config.qy,
            arModel.config.qz,
            arModel.config.qw,
            _heading,
            1f,
            1f,
            1f
        )] =
            earth.createAnchor(
                arModel.config.l1,
                arModel.config.l2,
                altitude,
                arModel.config.qx,
                arModel.config.qy,
                arModel.config.qz,
                arModel.config.qw
            )
    }

    fun clearAllMapPolylines() {
//        options = PolylineOptions()
        polylines.forEach {
            it.remove()
        }
    }

    fun clearAllAnchors() {
        val earth = session?.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) {
            return
        }
        earthAnchors.forEach {
            it.detach()
        }
        earthAnchors = mutableListOf()
    }

    fun clearAllAnchorsWithConfig() {
        val earth = session?.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) {
            return
        }
        earthAnchorsWithConfig.forEach {
            it.value.detach()
        }
        earthAnchorsWithConfig = mutableMapOf()
    }


    fun onMapClick(latLng: LatLng) {
        // TODO: place an anchor at the given position.
        return // DO not do anything
        val earth = session?.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) {
            return
        }


        // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
        val altitude = earth.cameraGeospatialPose.altitude - configAltitude;
        // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
        val qx = 0f
        val qy = sin((Math.PI - Math.toRadians(earth.cameraGeospatialPose.heading)) / 2).toFloat()
        val qz = 0f
        val qw = cos((Math.PI - Math.toRadians(earth.cameraGeospatialPose.heading)) / 2).toFloat()

//    earthAnchors.add(earth.createAnchor("22.322030".toDouble(),"114.208931".toDouble(), altitude, qx, qy, qz, qw))
//    val p1lat = 22.321995896012986
//    val p1lng = 114.20941475275107
//
//    val p2lat = 22.322049297911605
//    val p2lng = 114.2091872254686
        val p1lat = 22.3221447
        val p1lng = 114.2091502


        val p2lat = 22.3222412
        val p2lng = 114.2089846
        clearAllAnchors()
        clearAllMapPolylines()
        clearAllAnchorsWithConfig()

        updateGeospatialState(earth)
//        earthAnchorsWithConfig[AnchorConfig(0f, 1f, 1f, 1f)] =
//            earth.createAnchor(22.3221447, 114.2091502, altitude, 0f, 0f, 0f, 0f)
//
//        earthAnchorsWithConfig[AnchorConfig(0f, 2f, 1f, 1f)] =
//            earth.createAnchor(22.3222412, 114.2089846, altitude, 0f, 0f, 0f, 0f)


//        earthAnchors.add(earth.createAnchor(22.3221447, 114.2091502, altitude, 0f, 0f, 0f, 0f))
//        earthAnchors.add(earth.createAnchor(22.3222412, 114.2089846, altitude, qx, qy, qz, qw))
//        earthAnchorsWithConfig[AnchorConfig(79.902f, 36.735f, 1f, 1f)] =
//            earth.createAnchor(22.32175875, 114.20882041, altitude, sin(79.902f/2), sin(79.902f/2)*0, sin(79.902f/2)*0, cos(79.902f/2))
//        earthAnchorsWithConfig[AnchorConfig(165.384f, 29.797f, 1f, 1f)] =
//            earth.createAnchor(22.32181685, 114.20917147, altitude, sin(165.384f/2), sin(165.384f/2)*0, sin(165.384f/2)*0, cos(165.384f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(269.865f, 41.552f, 1f, 1f)] =
//            earth.createAnchor(22.32155645, 114.20924439, altitude, sin(269.865f/2), sin(269.865f/2)*0, sin(269.865f/2)*0, cos(269.865f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(354.583f, 22.591f, 1f, 1f)] =
//            earth.createAnchor(22.32155564, 114.20884107, altitude, sin(354.583f/2), sin(354.583f/2)*0, sin(354.583f/2)*0, cos(354.583f/2))


////SET 2
//        earthAnchorsWithConfig[AnchorConfig(79.902f, 36.735f, 1f, 1f)] =
//            earth.createAnchor(22.32175875, 114.20882041, altitude, 0f, sin(79.902f/2), 0f, cos(79.902f/2))
//        earthAnchorsWithConfig[AnchorConfig(165.384f, 29.797f, 1f, 1f)] =
//            earth.createAnchor(22.32181685, 114.20917147, altitude, 0f, sin(165.384f/2), 0f, cos(165.384f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(269.865f, 41.552f, 1f, 1f)] =
//            earth.createAnchor(22.32155645, 114.20924439, altitude, 0f, sin(269.865f/2), 0f, cos(269.865f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(354.583f, 22.591f, 1f, 1f)] =
//            earth.createAnchor(22.32155564, 114.20884107, altitude, 0f, sin(354.583f/2), 0f, cos(354.583f/2))

        ////SET 2 END

        ////SET 3


//        earthAnchors.add(
//            earth.createAnchor(
//                22.32175875,
//                114.20882041,
//                altitude,
//                qx,
//                qx,
//                qz,
//                qw
//            ))


//        var _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(80.603f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(_heading, 36.736f, 1f, 1f)] =
//            earth.createAnchor(
//                22.32175875,
//                114.20882041,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )

//        _heading = BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(164.356f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(_heading, 29.798f, 1f, 1f)] =
//            earth.createAnchor(
//                22.32181685,
//                114.20917147,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )
//
//        _heading = BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(269.885f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(_heading, 41.554f, 1f, 1f)] =
//            earth.createAnchor(
//                22.32155645,
//                114.20924439,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )
//
//        _heading = BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(354.193f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(_heading, 22.591f, 1f, 1f)] =
//            earth.createAnchor(
//                22.32155564,
//                114.20884107,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )

//        earthAnchorsWithConfig[AnchorConfig(80.603f, 36.736f, 1f, 1f)] =
//            earth.createAnchor(22.32175875, 114.20882041, altitude, 0f, sin(80.603f/ 2), 0f, cos(80.603f/2))

//        earthAnchorsWithConfig[AnchorConfig(164.356f, 29.798f, 1f, 1f)] =
//            earth.createAnchor(22.32181685, 114.20917147, altitude, 0f, sin(164.356f/2), 0f, cos(164.356f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(269.885f, 41.554f, 1f, 1f)] =
//            earth.createAnchor(22.32155645, 114.20924439, altitude, 0f, sin(269.885f/2), 0f, cos(269.885f/2))
//
//        earthAnchorsWithConfig[AnchorConfig(354.193f, 22.591f, 1f, 1f)] =
//            earth.createAnchor(22.32155564, 114.20884107, altitude, 0f, sin(354.193f/2), 0f, cos(354.193f/2))

//        earthAnchorsWithConfig[AnchorConfig(80.603f, 36.736f, 1f, 1f)] =
//            earth.createAnchor(22.32175875, 114.20882041, altitude, 0f, 0f, 0f, 0f)
//
//        earthAnchorsWithConfig[AnchorConfig(164.356f, 29.798f, 1f, 1f)] =
//            earth.createAnchor(22.32181685, 114.20917147, altitude, 0f, 0f, 0f, 0f)
//
//        earthAnchorsWithConfig[AnchorConfig(269.885f, 41.554f, 1f, 1f)] =
//            earth.createAnchor(22.32155645, 114.20924439, altitude, 0f, 0f, 0f, 0f)
//
//        earthAnchorsWithConfig[AnchorConfig(354.193f, 22.591f, 1f, 1f)] =
//            earth.createAnchor(22.32155564, 114.20884107, altitude, 0f, 0f, 0f, 0f)


        ////SET 3 END

//        var qw2 = cos((Math.PI - Math.toRadians((-79.902f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//        var qy2 = sin((Math.PI - Math.toRadians((-79.902f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//        earthAnchorsWithConfig[AnchorConfig(79.902f, 36.735f, 1f, 1f)] =
//            earth.createAnchor(22.32175875, 114.20882041, altitude, 0f, qy2, 0f, qw2)
//
//         qw2 = cos((Math.PI - Math.toRadians((-165.384f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//         qy2 = sin((Math.PI - Math.toRadians((-165.384f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//        earthAnchorsWithConfig[AnchorConfig(165.384f, 29.797f, 1f, 1f)] =
//            earth.createAnchor(22.32181685, 114.20917147, altitude, 0f, qy2, 0f, qw2)
//
//         qw2 = cos((Math.PI - Math.toRadians((-269.865f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//         qy2 = sin((Math.PI - Math.toRadians((-269.865f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//        earthAnchorsWithConfig[AnchorConfig(269.865f, 41.552f, 1f, 1f)] =
//            earth.createAnchor(22.32155645, 114.20924439, altitude, 0f, qy2, 0f, qw2)
//
//         qw2 = cos((Math.PI - Math.toRadians((-354.583f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//         qy2 = sin((Math.PI - Math.toRadians((-354.583f / 360 + 180).roundToInt().toDouble())) / 2).toFloat()
//        earthAnchorsWithConfig[AnchorConfig(354.583f, 22.591f, 1f, 1f)] =
//            earth.createAnchor(22.32155564, 114.20884107, altitude, 0f, qy2, 0f, qw2)


//        earth.createAnchor(22.321522313545632, 114.20767595770572, altitude, qx, qy, qz, qw);
//        earth.createAnchor(22.321633410564374, 114.20897289458901, altitude, qx, qy, qz, qw);
//        earth.createAnchor(22.320404715195142, 114.20908881428933, altitude, qx, qy, qz, qw);
//        earth.createAnchor(22.32030134438613, 114.20779733245074, altitude, qx, qy, qz, qw);
//        22.321522313545632, 114.20767595770572
//        22.321633410564374, 114.20897289458901
//        22.320404715195142, 114.20908881428933
//        22.32030134438613, 114.20779733245074
//        drawLine(p1lat, p1lng, p2lat, p2lng, altitude, qx, qy, qz, qw);
//        drawLine(p1lat, p1lng, p2lat, p2lng, altitude, qx, qy, qz, qw);
//        val boundary = listOf<LatLng>(
//            LatLng(22.321522313545632, 114.20767595770572),
//            LatLng(22.321633410564374, 114.20897289458901),
//            LatLng(
//                22.320404715195142, 114.20908881428933
//            ),
//            LatLng(22.32030134438613, 114.20779733245074)
//        )
//        drawBoundary(boundary, altitude, qx, qy, qz, qw);


        //        var _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(80.603f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(_heading, 36.736f, 1f, 1f)] =
//            earth.createAnchor(
//                22.32175875,
//                114.20882041,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )


//SET 5

//        var _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(80.603f, 90f))
////        earthAnchorsWithConfig[AnchorConfig(_heading, 36.736f*2, 1f, 1f)] =
//        earthAnchorsWithConfig[AnchorConfig(
//            _heading,
//            36.736f * 10,
//            configModelScaleY,
//            configModelScaleZ
//        )] =
//            earth.createAnchor(
//                22.3217878,
//                114.20899594,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )
//        _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(164.356f, 90f))
////        earthAnchorsWithConfig[AnchorConfig(_heading, 29.798f*2, 1f, 1f)] =
//        earthAnchorsWithConfig[AnchorConfig(
//            _heading,
//            29.798f * 10,
//            configModelScaleY,
//            configModelScaleZ
//        )] =
//            earth.createAnchor(
//                22.32168665,
//                114.20920793,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )
//
//        _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(269.885f, 90f))
////        earthAnchorsWithConfig[AnchorConfig(_heading, 41.554f*2, 1f, 1f)] =
//        earthAnchorsWithConfig[AnchorConfig(
//            _heading,
//            41.554f * 10,
//            configModelScaleY,
//            configModelScaleZ
//        )] =
//            earth.createAnchor(
//                22.321556045, 114.20904273,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )
////
//        _heading =
//            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(354.193f, 90f))
//        earthAnchorsWithConfig[AnchorConfig(
//            _heading,
//            22.591f * 10,
//            configModelScaleY,
//            configModelScaleZ
//        )] =
//            earth.createAnchor(
//                22.321657195, 114.20883074,
//                altitude,
//                0f,
//                sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat(),
//                0f,
//                cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
//            )

        //DEMO 6
//        addLineModel(22.3217878, 114.20899594, 80.603f, 36.736f)
//        addLineModel(22.32168665, 114.20920793, 164.356f, 29.798f)
//        addLineModel(22.321556045, 114.20904273, 269.885f, 41.554f)
//        addLineModel(22.321657195, 114.20883074, 354.193f, 22.591f)
//        fragment.view.mapView!!.googleMap.addPolyline(
//            PolylineOptions().add(
//                LatLng(22.3217878, 114.20899594),
//                LatLng(22.32168665, 114.20920793),
//                LatLng(22.321556045, 114.20904273),
//                LatLng(22.321657195, 114.20883074),
//                LatLng(22.3217878, 114.20899594),
//            )
//        )

        fragment.view.mapView?.earthMarker?.apply {
            position = latLng
            isVisible = false
        }
    }

    fun updateGeospatialState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose;
        if (geospatialPose.horizontalAccuracy <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS && geospatialPose.headingAccuracy <= LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES) {
            fragment.view.updateInformationText("Accuracy Threshold Passed")
        } else {
            fragment.view.updateInformationText("Accuracy Threshold Not Passed. Point your camera at building stores, and signs near you. ")
        }
    }

    fun getMetersBetweenAnchors(anchor1: Anchor, anchor2: Anchor): Float {
        val distance_vector = anchor1.pose.inverse()
            .compose(anchor2.pose).translation
        var totalDistanceSquared = 0f
        for (i in 0..2) totalDistanceSquared += distance_vector[i] * distance_vector[i]
        return sqrt(totalDistanceSquared.toDouble()).toFloat()
    }


    private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
        renderCompassAtAnchor(anchor, null)
    }

    private fun SampleRender.renderDemoModelAtAnchor(anchor: Anchor, anchorConfig: AnchorConfig) {

        anchor.pose.toMatrix(modelMatrix, 0)

        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)

        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)


        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        draw(virtualDemoModelObjectMesh, virtualDemoModelObjectShader, virtualSceneFramebuffer)
    }

    private fun SampleRender.renderCompassAtAnchor(anchor: Anchor, anchorConfig: AnchorConfig?) {
        // Get the current pose of the Anchor in world space. The Anchor pose is updated
        // during calls to session.update() as ARCore refines its estimate of the world.
        anchor.pose.toMatrix(modelMatrix, 0)

        if (anchorConfig != null) {
            Matrix.scaleM(
                modelMatrix,
                0,
                anchorConfig.scaleX,
                anchorConfig.scaleY,
                anchorConfig.scaleZ
            );
        }


        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)

        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
//        val vo = virtualObjects.random()
//        vo.objectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
//        draw(vo.objectMesh, vo.objectShader, virtualSceneFramebuffer)
    }

    private fun showError(errorMessage: String) =
        fragment.view.snackbarHelper.showError(fragment.requireActivity(), errorMessage)
}

class AnchorConfig(
    val l1: Double, val l2: Double,
    val altitude: Double,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float, val heading: Double, val scaleX: Float, val scaleY: Float, val scaleZ: Float
) {

}

class RenderObject(
    val virtualObjectMesh: Mesh,
    var virtualObjectShader: Shader,
    var virtualObjectTexture: Texture
) {

}