package com.mapking.ar.core.landmark.geo

import android.net.Uri
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.mapking.ar.core.landmark.geo.common.helpers.DisplayRotationHelper
import com.mapking.ar.core.landmark.geo.common.helpers.TrackingStateHelper
import com.mapking.ar.core.landmark.geo.common.samplerender.*
import com.mapking.ar.core.landmark.geo.common.samplerender.arcore.BackgroundRenderer
import com.mapking.ar.core.landmark.geo.helpers.BearingUtils
import com.opencsv.CSVReader
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SettingRenderer(val activity: SettingActivity) :
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

    // Virtual object (ARCore pawn)
    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader
    lateinit var virtualObjectTexture: Texture
    lateinit var sampleRender: SampleRender


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

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects.
        // This involves reading shaders and 3D model files, so may throw an IOException.
        try {
            sampleRender = render
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

            // Virtual object to render (Geospatial Marker)
            virtualObjectTexture =
                Texture.createFromAsset(
                    render,
                    "models/spatial_marker_house.jpg",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            virtualObjectMesh = Mesh.createFromAsset(
                render,
                "models/house.obj"
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


            backgroundRenderer.setUseDepthVisualization(render, false)
            backgroundRenderer.setUseOcclusion(render, false)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    fun drawDemoModel() {

        val earth = session?.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) {
            return
        }
        clearAllAnchors()
        clearAllAnchorsWithConfig()

        updateGeospatialState(earth)
        addLineModel(22.2922781, 114.2085679, 0f, 1f)
    }

    private fun redrawArModel() {
        if (demoModel != null) {
            demoModel!!.anchor.detach()
            drawDemoModel()
        }
    }

    fun redrawAll() {
        redrawArModel()
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

    fun drawFromUri(uri: Uri) {
        val earth = session?.earth ?: return
//        Will be paused after choosing the file
//        if (earth.trackingState != TrackingState.TRACKING) {
//            return
//        }

        clearAllAnchors()
        clearAllAnchorsWithConfig()
        updateGeospatialState(earth)
        try {
            val reader = CSVReader(
                BufferedReader(
                    InputStreamReader(
                        activity.contentResolver.openInputStream(uri)
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

            for (nextLine in reader.iterator()) {
                if (!readFirstLine) {
                    readFirstLine = true
                    nextLine.forEachIndexed { index, element ->
                        keys[element] = index
                    }
                } else {
                    val l2 = if (keys["xc"] != null) nextLine[keys["xc"]!!].toDouble() else 0.0
                    val l1 = if (keys["yc"] != null) nextLine[keys["yc"]!!].toDouble() else 0.0
                    val b =
                        if (keys["bearing"] != null) nextLine[keys["bearing"]!!].toFloat() else 0f
                    val l = if (keys["length"] != null) nextLine[keys["length"]!!].toFloat() else 0f
                    addLineModel(l1, l2, b, l)
                }
            }
            activity.view.snackbarHelper.showMessageWithDismiss(
                activity,
                "CSV Successfully loaded"
            )

        } catch (e: Exception) {
            activity.view.snackbarHelper.showError(
                activity,
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
        val _heading =
            BearingUtils.getHeadingFromBearing(BearingUtils.getBearingWithSubtract(bearing, 90f))
        val qx = 0f
        val qy = sin((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        val qz = 0f
        val qw = cos((Math.PI - Math.toRadians(_heading)) / 2).toFloat()
        earthAnchorsWithConfig[AnchorConfig(
            l1, l2, altitude, qx, qy, qz, qw,
            _heading,
            1f,
            1f,
            1f
        )] =
            earth.createAnchor(
                l1+configControlX,
                l2+configControlY,
                altitude,
                qx,
                qy,
                qz,
                qw
            )
    }

    private fun addLineModel(config: AnchorConfig) {
        val earth = session?.earth ?: return
        earthAnchorsWithConfig[config] =
            earth.createAnchor(
                config.l1+configControlX,
                config.l2+configControlY,
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

    override fun onDrawFrame(render: SampleRender) {
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
        val frame =
            try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during onDrawFrame", e)
                showError("Camera not available. Try restarting the app.")
                return
            } catch (e: SessionPausedException) {
                Log.e(TAG, "Session paused", e)
                return
            }

        val camera = frame.camera
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

        val earth = session.earth
        if (earth?.trackingState == TrackingState.TRACKING) {
            // TODO: the Earth object may be used here.
            val cameraGeospatialPose = earth.cameraGeospatialPose

            activity.view.mapView?.updateMapPosition(
                latitude = cameraGeospatialPose.latitude,
                longitude = cameraGeospatialPose.longitude,
                heading = cameraGeospatialPose.heading
            )
        }
        if (earth != null) {
            activity.view.updateStatusText(earth, earth.cameraGeospatialPose)
        }

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


    }

    var demoModel: DemoModel? = null
    var demoModelsWithConfig: MutableMap<AnchorConfig, Anchor> = mutableMapOf()
    var arModelsWithAnchor: MutableMap<ArModel, Anchor> = mutableMapOf()
    var earthAnchors: MutableList<Anchor> = mutableListOf()
    var earthAnchorsWithConfig: MutableMap<AnchorConfig, Anchor> = mutableMapOf()

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
        clearAllAnchorsWithConfig()

        updateGeospatialState(earth)
        //DEMO 6
//        addLineModel(22.29103237, 114.2097369, 74.033f, 15.115f)
        addLineModel(22.321657195, 114.20883074, 354.193f, 22.591f)

        activity.view.mapView?.earthMarker?.apply {
            position = latLng
            isVisible = false
        }
    }

    fun updateGeospatialState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose;
        if (geospatialPose.horizontalAccuracy <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS && geospatialPose.headingAccuracy <= LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES) {
            activity.view.updateInformationText("Accuracy Threshold Passed")
        } else {
            activity.view.updateInformationText("Accuracy Threshold Not Passed. Point your camera at building stores, and signs near you. ")
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
        draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
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

        Log.d(TAG, "renderCompassAtAnchor: modelMatrix" + modelMatrix.contentToString())

        // Calculate model/view/projection matrices
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Log.d(TAG, "renderCompassAtAnchor: modelViewMatrix" + modelViewMatrix.contentToString())
        Log.d(TAG, "renderCompassAtAnchor: viewMatrix" + viewMatrix.contentToString())
        Log.d(TAG, "renderCompassAtAnchor: modelMatrix" + modelMatrix.contentToString())

        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)


        // Update shader properties and draw
        virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
    }

    private fun showError(errorMessage: String) =
        activity.view.snackbarHelper.showError(activity, errorMessage)
}
