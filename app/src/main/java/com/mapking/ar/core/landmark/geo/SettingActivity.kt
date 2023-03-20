package com.mapking.ar.core.landmark.geo

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.slider.Slider
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*
import com.mapking.ar.core.landmark.geo.common.samplerender.SampleRender
import com.mapking.ar.core.landmark.geo.helpers.ARCoreSessionLifecycleHelper
import com.mapking.ar.core.landmark.geo.helpers.GeoPermissionsHelper
import com.mapking.ar.core.landmark.geo.helpers.SettingView
import kotlinx.android.synthetic.main.fragment_main.view.*
import java.util.*

class SettingActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SettingActivity"
    }

    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: SettingView
    lateinit var renderer: SettingRenderer

    var loadSingleModelLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                if (data != null) {
                    renderer.drawSingleModel(data.data!!)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        view = SettingView(this)
        lifecycle.addObserver(view)
        setContentView(view.root)
        // Setup ARCore session lifecycle helper and configuration.
        arCoreSessionHelper =
            ARCoreSessionLifecycleHelper(
                this,
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
                view.snackbarHelper.showError(this, message)
            }

        // Configure session features.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
        lifecycle.addObserver(arCoreSessionHelper)

        // Set up the Main AR renderer.
        renderer = SettingRenderer(this)
        lifecycle.addObserver(renderer)


        view.btnToggle.setOnClickListener(View.OnClickListener {
            view.llInput.visibility = if (view.llInput.visibility == View.VISIBLE) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
        })

        view.btnBack.setOnClickListener {
            onBackPressed()
        }

//        val navController = Navigation.findNavController(root)
//        val appBarConfiguration = AppBarConfiguration.Builder(navController.graph).build()
//        NavigationUI.setupWithNavController(root.toolbar, navController, appBarConfiguration)

        view.btnClearAll.setOnClickListener {
            renderer.clearAllAnchors()
            renderer.clearAllAnchorsWithConfig()
        }

        view.btnResetAltitude.setOnClickListener {
            view.sliderAltitude.value = renderer.defaultAltitude
        }

        view.btnSetting.setOnClickListener {
            val intent = Intent(this, SettingActivity::class.java)
            startActivity(intent)

        }

        view.btnControlUp.setOnClickListener {
            renderer.configControlY = renderer.configControlY+ renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlRight.setOnClickListener {
            renderer.configControlX = renderer.configControlX+renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlReset.setOnClickListener {
            renderer.configControlX = renderer.configControlDefault
            renderer.configControlY = renderer.configControlDefault
            renderer.redrawAll()
        }

        view.btnControlDown.setOnClickListener {
            renderer.configControlY = renderer.configControlY-renderer.configControlStep
            renderer.redrawAll()
        }

        view.btnControlLeft.setOnClickListener {
            renderer.configControlX = renderer.configControlX-renderer.configControlStep
            renderer.redrawAll()
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

        // Sets up an example renderer using our HelloGeoRenderer.
        SampleRender(view.surfaceView, renderer, assets)

    }

    // Configure the session, setting the desired options according to your usecase.
    fun configureSession(session: Session) {
        // TODO: Configure ARCore to use GeospatialMode.ENABLED.
        session.configure(
            session.config.apply {
                // Enable Geospatial Mode.
                geospatialMode = Config.GeospatialMode.ENABLED
            }
        )
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            view.snackbarHelper.showError(
                this,
                "Camera and location permissions are needed to run this application"
            )
            if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                GeoPermissionsHelper.launchPermissionSettings(this)
            }
        }

    }
}