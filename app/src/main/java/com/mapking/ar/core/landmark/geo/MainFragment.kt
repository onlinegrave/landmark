package com.mapking.ar.core.landmark.geo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.PatternItem
import com.google.android.material.slider.Slider
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.mapking.ar.core.landmark.geo.common.samplerender.SampleRender
import com.mapking.ar.core.landmark.geo.databinding.FragmentMainBinding
import com.mapking.ar.core.landmark.geo.dialogs.AddLineDialog
import com.mapking.ar.core.landmark.geo.dialogs.AddLineResult
import com.mapking.ar.core.landmark.geo.helpers.*
import kotlinx.android.synthetic.main.fragment_main.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.joda.time.DateTime
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference


class MainFragment : BaseFragment(), AddLineDialog.AddLineDialogListener {
    private var _fragmentMainBinding: FragmentMainBinding? = null
    private val fragmentMainBinding get() = _fragmentMainBinding!!

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentMainBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentMainBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentMainBinding.overlay.background = null
            }, MainActivity.ANIMATION_FAST_MILLIS)
        }
    }

    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper

    lateinit var compassSensorLifecycleHelper: CompassSensorLifecycleHelper

    lateinit var view: MainFragmentView
    lateinit var renderer: HelloGeoRenderer
    val captureRequest = AtomicReference(false)
    val config = setOf(Config.GeospatialMode.ENABLED, Config.FocusMode.FIXED)

    var loadBoundaryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                if (data != null) {
                    renderer.drawFromUri(data.data!!)
                }
            }
        }

    var loadSingleModelLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                if (data != null) {
                    renderer.drawSingleModel(data.data!!)
                }
            }
        }


    companion object {
        private const val TAG = "MainFragment"

        const val COLOR_BLACK_ARGB = -0x1000000
        const val POLYLINE_STROKE_WIDTH_PX = 12
        const val COLOR_WHITE_ARGB = -0x1
        const val COLOR_DARK_GREEN_ARGB = -0xc771c4
        const val COLOR_LIGHT_GREEN_ARGB = -0x7e387c
        const val COLOR_DARK_ORANGE_ARGB = -0xa80e9
        const val COLOR_LIGHT_ORANGE_ARGB = -0x657db

        const val PATTERN_GAP_LENGTH_PX = 20
        val DOT: PatternItem = Dot()
        val GAP: PatternItem = Gap(PATTERN_GAP_LENGTH_PX.toFloat())

        // Create a stroke pattern of a gap followed by a dot.
        private val PATTERN_POLYLINE_DOTTED = listOf(GAP, DOT)

        const val POLYGON_STROKE_WIDTH_PX = 8
        const val PATTERN_DASH_LENGTH_PX = 20
        val DASH: PatternItem = Dash(PATTERN_DASH_LENGTH_PX.toFloat())

        // Create a stroke pattern of a gap followed by a dash.
        val PATTERN_POLYGON_ALPHA =
            listOf(MainFragment.GAP, DASH)

        // Create a stroke pattern of a dot followed by a gap, a dash, and another gap.
        val PATTERN_POLYGON_BETA = listOf(
            MainFragment.DOT,
            MainFragment.GAP,
            DASH,
            MainFragment.GAP
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }
    }

    // Configure the session, setting the desired options according to your usecase.
    fun configureSession(session: Session) {
        val filter = CameraConfigFilter(session)
        filter.targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
        filter.depthSensorUsage = EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE)
        val cameraConfigList = session.getSupportedCameraConfigs(filter)
        session.cameraConfig = cameraConfigList[0]
        session.configure(
            session.config.apply {
                // Enable Geospatial Mode.
                geospatialMode = Config.GeospatialMode.ENABLED
                augmentedFaceMode = Config.AugmentedFaceMode.DISABLED
                depthMode = Config.DepthMode.DISABLED
                focusMode = Config.FocusMode.FIXED
                cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                instantPlacementMode = Config.InstantPlacementMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            }
        )
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!GeoPermissionsHelper.hasGeoPermissions(requireActivity())) {
            // Use toast instead of snackbar here since the activity will exit.
            view.snackbarHelper.showError(
                requireActivity(),
                "Camera and location permissions are needed to run this application"
            )
            if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(requireActivity())) {
                // Permission denied with checking "Do not ask again".
                GeoPermissionsHelper.launchPermissionSettings(requireActivity())
            }
        }

    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentMainBinding = FragmentMainBinding.inflate(inflater, container, false)
        val root = fragmentMainBinding.root
        // Setup Compass session helper and configuraton


        // Setup ARCore session lifecycle helper and configuration.
        arCoreSessionHelper =
            ARCoreSessionLifecycleHelper(
                requireActivity(),
                EnumSet.of(Session.Feature.SHARED_CAMERA)
            )
        // If Session creation or Session.resume() fails, display a message and log detailed
        // information.
        arCoreSessionHelper.exceptionCallback =
            { exception ->
                val message =
                    when (exception) {
                        is UnavailableUserDeclinedInstallationException ->
                            "Please install Google Play Services for AR"
                        is UnavailableApkTooOldException -> "Please update ARCore"
                        is UnavailableSdkTooOldException -> "Please update this app"
                        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                        is CameraNotAvailableException -> "Camera not available. Try restarting the app."
                        else -> "Failed to create AR session: $exception"
                    }
                Log.e(TAG, "ARCore threw an exception", exception)
                view.snackbarHelper.showError(requireActivity(), message)
            }

        // Configure session features.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)


        // Set up the Main AR renderer.
        renderer = HelloGeoRenderer(this)
        lifecycle.addObserver(renderer)

        compassSensorLifecycleHelper = CompassSensorLifecycleHelper(requireActivity())
        lifecycle.addObserver(compassSensorLifecycleHelper)

        // Set up Main AR UI.
        view = MainFragmentView(this, fragmentMainBinding)
        lifecycle.addObserver(view)

        view.btnToggle.setOnClickListener(View.OnClickListener {
            view.llInput.visibility = if (view.llInput.visibility == View.VISIBLE) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        })

//        val navController = Navigation.findNavController(root)
//        val appBarConfiguration = AppBarConfiguration.Builder(navController.graph).build()
//        NavigationUI.setupWithNavController(root.toolbar, navController, appBarConfiguration)

        view.btnClearAll.setOnClickListener {
            renderer.clearAllAnchors()
            renderer.clearAllAnchorsWithConfig()
            renderer.clearAllMapPolylines()
        }

        view.btnResetAltitude.setOnClickListener {
            view.sliderAltitude.value = renderer.defaultAltitude
        }


        view.btnResetAll.setOnClickListener {
            // Order matters
            it.isEnabled = false
            if (arCoreSessionHelper.session != null) {
                val earth = arCoreSessionHelper.session?.earth
                if (earth?.earthState == Earth.EarthState.ENABLED) {
                    var copy: MutableMap<AnchorConfig, Anchor> = mutableMapOf()
                    copy.putAll(renderer.earthAnchorsWithConfig)
                    renderer.clearAllAnchorsWithConfig()

                    arCoreSessionHelper.onPause(this)
                    renderer.onPause(this)
                    view.onPause(this)

                    view.sliderAltitude.value = renderer.defaultAltitude
                    renderer.configControlX = renderer.configControlDefault
                    renderer.configControlY = renderer.configControlDefault

                    arCoreSessionHelper.onResume(this)
                    renderer.onResume(this)
                    view.onResume(this)


                    copy.forEach {
                        val _key = it.key
                        renderer.addLineModel(
                            AnchorConfig(
                                _key.l1,
                                _key.l2,
                                earth.cameraGeospatialPose.altitude - renderer.configAltitude,
                                _key.qx,
                                _key.qy,
                                _key.qz,
                                _key.qw,
                                _key.heading,
                                _key.scaleX,
                                renderer.configModelScaleY,
                                renderer.configModelScaleZ
                            )
                        )
                    }
                }
            }
            it.isEnabled = true
        }

        view.btnCapture.setOnClickListener {
            it.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                fragmentMainBinding.surfaceview.post(animationTask)
                val bitmap = Bitmap.createBitmap(
                    fragmentMainBinding.surfaceview.width,
                    fragmentMainBinding.surfaceview.height,
                    Bitmap.Config.ARGB_8888
                )
                val handler = Handler(Looper.getMainLooper())
                PixelCopy.request(
                    fragmentMainBinding.surfaceview,
                    bitmap,
                    PixelCopy.OnPixelCopyFinishedListener {
                        val date = DateTime.now().toString("yyyy-MM-dd-HH-mm-ss")
                        val filename = String.format(
                            Locale.ENGLISH,
                            "arcore_image_%s.png",
                            date
                        )
                        val file = File(requireActivity().getExternalFilesDir(null), filename)
                        StorageHelper.saveBitmap(file, bitmap)

                        val viewMatrixFilename = String.format(
                            Locale.ENGLISH,
                            "view_matrix_%s.txt", date
                        )
                        val viewMatrixFile =
                            File(requireActivity().getExternalFilesDir(null), viewMatrixFilename)
                        StorageHelper.saveFloatArray(viewMatrixFile, renderer.viewMatrix)

                        val projectionMatrixFilename = String.format(
                            Locale.ENGLISH,
                            "projection_matrix_%s.txt", date
                        )
                        val projectionMatrixFile =
                            File(
                                requireActivity().getExternalFilesDir(null),
                                projectionMatrixFilename
                            )
                        StorageHelper.saveFloatArray(
                            projectionMatrixFile,
                            renderer.projectionMatrix
                        )

                        val modelViewProjectionMatrix = String.format(
                            Locale.ENGLISH,
                            "model_view_projection_matrix_%s.txt", date
                        )
                        val modelViewProjectionMatrixFile =
                            File(
                                requireActivity().getExternalFilesDir(null),
                                modelViewProjectionMatrix
                            )
                        StorageHelper.saveFloatArray(
                            modelViewProjectionMatrixFile,
                            renderer.modelViewProjectionMatrix
                        )

                        val cameraViewMatrixFilename = String.format(
                            Locale.ENGLISH,
                            "camera_view_matrix_%s.txt", date
                        )
                        val cameraViewMatrix = FloatArray(16)
                        renderer.camera.getViewMatrix(cameraViewMatrix, 0)
                        val cameraViewMatrixFile =
                            File(
                                requireActivity().getExternalFilesDir(null),
                                cameraViewMatrixFilename
                            )
                        StorageHelper.saveFloatArray(
                            cameraViewMatrixFile,
                            cameraViewMatrix
                        )


                        val cameraProjectionMatrixFilename = String.format(
                            Locale.ENGLISH,
                            "camera_projection_matrix_%s.txt", date
                        )
                        val cameraProjectionMatrix = FloatArray(16)
                        renderer.camera.getProjectionMatrix(cameraProjectionMatrix, 0, 0f, 0f)

                        val cameraProjectionMatrixFile =
                            File(
                                requireActivity().getExternalFilesDir(null),
                                cameraProjectionMatrixFilename
                            )
                        StorageHelper.saveFloatArray(
                            cameraProjectionMatrixFile,
                            cameraProjectionMatrix
                        )

                        val geoPoseFilename = String.format(
                            Locale.ENGLISH,
                            "geoPoseFilename_%s.txt", date
                        )
                        val geoPoseFile =
                            File(
                                requireActivity().getExternalFilesDir(null),
                                geoPoseFilename
                            )
                        StorageHelper.saveCameraGeospatialPose(
                            geoPoseFile,
                            renderer.session!!.earth!!.cameraGeospatialPose
                        )
                    },
                    handler
                )

                it.post { it.isEnabled = true }
                /**
                val bitmap = ScreenShot.takeScreenshot(fragmentMainBinding.surfaceViewContainer)
                val filename = String.format(
                Locale.ENGLISH,
                "arcore_image_%s.jpeg",
                DateTime.now().toString("yyyy-MM-dd-HH-mm-ss")
                )
                //            val file = File(requireActivity().getExternalFilesDir(null), filename)
                //            StorageHelper.saveBitmap(file, bitmap)
                screenshot(fragmentMainBinding.surfaceview,filename)
                 */

//            captureRequest.set(true)

//            val image = renderer.lastCameraImage
//            if (image != null) {
//                val buffer = image.planes[0].buffer
//                val bytes = ByteArray(buffer.remaining())
//                buffer.get(bytes);
//                val bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//                val dir = context!!.getDir("images", Context.MODE_PRIVATE)
//                val mypath = File(dir, UUID.randomUUID().toString())
//                try {
//                    FileOutputStream(mypath).use { out ->
//                        bitmapImage.compress(
//                            Bitmap.CompressFormat.PNG,
//                            100,
//                            out
//                        ) // bmp is your Bitmap instance
//                    }
//                } catch (e: IOException) {
//                    e.printStackTrace()
//                }
//            }


//            val captureRequest =
//                view.cameraCaptureSession!!.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
//            view.cameraCaptureSession?.capture(
//                captureRequest.build(),
//                object : CameraCaptureSession.CaptureCallback() {
//                    override fun onCaptureCompleted(
//                        session: CameraCaptureSession,
//                        request: CaptureRequest,
//                        result: TotalCaptureResult
//                    ) {
//                        super.onCaptureCompleted(session, request, result)
//                    }
//                },
//                null
//            )
//            val session = renderer.session
//            if(session != null) {
//                val sharedCamera = session.sharedCamera
//                Log.d(TAG, "onCreateView: "+sharedCamera)
//                val surfaceList = sharedCamera.arCoreSurfaces
//                val camera = renderer.camera
//                val cameraManager = requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
//                cameraManager.cameraIdList
                //                Log.d(TAG, "onCreateView: "+camera.displayOrientedPose)
//                Log.d(TAG, "onCreateView: "+camera.trackingState)
//                Log.d(TAG, "onCreateView: "+camera.getViewMatrix(renderer.viewMatrix, 0))
//                Log.d(TAG, "onCreateView: "+camera.pose)
//                view.snackbarHelper.showMessageWithDismiss(this, "What")

//            }
            }
        }

        view.btnSetting.setOnClickListener {
            val intent = Intent(requireActivity(), SettingActivity::class.java)
            startActivity(intent)

        }

        view.btnToggleAltitude.setOnClickListener {
            view.root.extra_card.visibility = if (view.root.extra_card.visibility == View.VISIBLE) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }


        }

        view.sliderAltitude.valueFrom = -50f
        view.sliderAltitude.valueTo = 50f
        view.sliderAltitude.value = renderer.configAltitude

        view.sliderAltitude.addOnChangeListener(Slider.OnChangeListener { slider, value, fromUser ->
            renderer.configAltitude = value
            renderer.redrawAll()
        })


        view.sliderModelY.valueFrom = 0f
        view.sliderModelY.valueTo = 10f
        view.sliderModelY.value = renderer.configModelScaleY
        view.sliderModelY.addOnChangeListener(Slider.OnChangeListener { slider, value, fromUser ->
            renderer.configModelScaleY = value
            renderer.redrawAll()
        })

        view.sliderModelZ.valueFrom = 0f
        view.sliderModelZ.valueTo = 10f
        view.sliderModelZ.value = renderer.configModelScaleZ
        view.sliderModelZ.addOnChangeListener(Slider.OnChangeListener { slider, value, fromUser ->
            renderer.configModelScaleZ = value
            renderer.redrawAll()
        })

        view.btnLoadingSingleModel.setOnClickListener {
//            val intent = Intent()
//                .setType("text/csv")
//                .setAction(Intent.ACTION_GET_CONTENT)
//                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                .addCategory(Intent.CATEGORY_OPENABLE)
//            loadSingleModelLauncher.launch(intent)
            renderer.drawDemoModel()
        }

        view.btnLoadModel.setOnClickListener {
            val intent = Intent()
                .setType("text/csv")
                .setAction(Intent.ACTION_GET_CONTENT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addCategory(Intent.CATEGORY_OPENABLE)
            loadBoundaryLauncher.launch(intent)

        }

        view.btnMenu.setOnClickListener {
            mainActivity.toggleDrawer()
        }

        view.btnAddLine.setOnClickListener {
            AddLineDialog().show(childFragmentManager, AddLineDialog.TAG)
        }

        view.btnControlUp.setOnClickListener {
            renderer.configControlY = renderer.configControlY + renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlRight.setOnClickListener {
            renderer.configControlX = renderer.configControlX + renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlReset.setOnClickListener {
            renderer.configControlX = renderer.configControlDefault
            renderer.configControlY = renderer.configControlDefault
            renderer.redrawAll()
        }

        view.btnControlDown.setOnClickListener {
            renderer.configControlY = renderer.configControlY - renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlLeft.setOnClickListener {
            renderer.configControlX = renderer.configControlX - renderer.configControlStep
            renderer.redrawAll()
        }

        // Sets up an example renderer using our HelloGeoRenderer.
        SampleRender(view.surfaceView, renderer, requireActivity().assets)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        val navController = findNavController()
//        val appBarConfiguration = AppBarConfiguration(navController.graph)
//        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentMainBinding = null
    }

    override fun onPositiveDialogClick(result: AddLineResult) {

    }


    protected fun screenshot(view: View, filename: String): File? {
        val date = Date()

        // Here we are initialising the format of our image name
        val format: CharSequence = DateFormat.format("yyyy-MM-dd_hh:mm:ss", date)
        try {
            // Initialising the directory of storage
            val dirpath: String = Environment.getExternalStorageDirectory().toString() + ""
            val file = File(dirpath)
            if (!file.exists()) {
                val mkdir: Boolean = file.mkdir()
            }

            // File name
            val path = "$dirpath/$filename-$format.jpeg"
            view.setDrawingCacheEnabled(true)
            val bitmap: Bitmap = Bitmap.createBitmap(view.getDrawingCache())
            view.setDrawingCacheEnabled(false)
            val imageurl = File(path)
            val outputStream = FileOutputStream(imageurl)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            outputStream.flush()
            outputStream.close()
            return imageurl
        } catch (io: FileNotFoundException) {
            io.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    override fun onNegativeDialogClick() {

    }
}