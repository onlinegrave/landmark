package com.mapking.ar.core.landmark.geo.helpers

import android.opengl.GLSurfaceView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.mapking.ar.core.landmark.geo.R
import com.mapking.ar.core.landmark.geo.SettingActivity
import com.mapking.ar.core.landmark.geo.SettingMapView
import com.mapking.ar.core.landmark.geo.common.helpers.SnackbarHelper

class SettingView(val activity: SettingActivity) : DefaultLifecycleObserver {
    val root = View.inflate(activity, R.layout.activity_setting, null)
    val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)
    val btnBack = root.findViewById<ImageView>(R.id.left_menu_iv)
    val btnLoadingSingleModel = root.findViewById<Button>(R.id.btn_load_single_model)
    val btnMenu = root.findViewById<ImageView>(R.id.left_menu_iv)
    val btnToggle = root.findViewById<Button>(R.id.btn_toggle)
    val btnClearAll = root.findViewById<Button>(R.id.btn_clear_all)
    val btnSetting = root.findViewById<Button>(R.id.btn_setting)
    val btnToggleAltitude = root.findViewById<Button>(R.id.btn_toggle_altitude)
    val sliderAltitude = root.findViewById<Slider>(R.id.slider_elevation)
    val btnResetAltitude = root.findViewById<Button>(R.id.btn_reset_altitude)
    val sliderModelY = root.findViewById<Slider>(R.id.slider_modely)
    val sliderModelZ = root.findViewById<Slider>(R.id.slider_modelz)

    val btnControlUp = root.findViewById<ImageView>(R.id.btn_control_move_up)
    val btnControlRight = root.findViewById<ImageView>(R.id.btn_control_move_right)
    val btnControlDown = root.findViewById<ImageView>(R.id.btn_control_move_down)
    val btnControlLeft = root.findViewById<ImageView>(R.id.btn_control_move_left)
    val btnControlReset = root.findViewById<MaterialTextView>(R.id.btn_control_reset)

    val llInput = root.findViewById<MaterialCardView>(R.id.container_input)


    val session get() = activity.arCoreSessionHelper.session
    val cameraCaptureSession get() = activity.arCoreSessionHelper.cameraCaptureSession

    val snackbarHelper = SnackbarHelper()

    var mapView: SettingMapView? = null
    val mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
        setup { screenLocation ->
            val latLng: LatLng =
                mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
            activity.renderer.onMapClick(latLng)
        }
    }
    val mapFragment =
        (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
            it.getMapAsync { googleMap ->
                mapView = SettingMapView(activity, googleMap)
            }
        }

    val statusText = root.findViewById<TextView>(R.id.status_text)
    val informationText = root.findViewById<TextView>(R.id.information_text);

    fun updateInformationText(text: String) {
        activity.runOnUiThread {
            informationText.text = text;
        }
    }

    fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
        activity.runOnUiThread {
            val poseText = if (cameraGeospatialPose == null) "" else
                activity.getString(
                    R.string.geospatial_pose,
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy
                )
            statusText.text = activity.resources.getString(
                R.string.earth_state,
                earth.earthState.toString(),
                earth.trackingState.toString(),
                poseText
            )
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        surfaceView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        surfaceView.onPause()
    }
}
