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
package com.mapking.ar.core.landmark.geo.helpers

import android.graphics.Bitmap
import android.os.Environment
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.ar.core.Camera
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.TrackingState
import com.mapking.ar.core.landmark.geo.MainActivity
import com.mapking.ar.core.landmark.geo.MainFragment
import com.mapking.ar.core.landmark.geo.R
import com.mapking.ar.core.landmark.geo.common.helpers.SnackbarHelper
import com.mapking.ar.core.landmark.geo.databinding.FragmentMainBinding
import kotlinx.android.synthetic.main.fragment_main.view.*
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.*


/** Contains UI elements for Main Fragment View. */
class MainFragmentView(val fragment: MainFragment, val fragmentMainBinding: FragmentMainBinding) :
    DefaultLifecycleObserver {
    val root = fragmentMainBinding.root
    val surfaceView = fragmentMainBinding.surfaceview
    val btnLoadingSingleModel = fragmentMainBinding.btnLoadSingleModel
    val btnMenu = fragmentMainBinding.leftMenuIv
    val btnLoadModel = fragmentMainBinding.btnLoadModel
    val btnAddLine = fragmentMainBinding.btnAddLine

    val btnControlUp = fragmentMainBinding.btnControlMoveUp
    val btnControlRight = fragmentMainBinding.btnControlMoveRight
    val btnControlReset = fragmentMainBinding.btnControlReset
    val btnControlDown = fragmentMainBinding.btnControlMoveDown
    val btnControlLeft = fragmentMainBinding.btnControlMoveLeft

    val btnToggle = fragmentMainBinding.btnToggle
    val btnClearAll = fragmentMainBinding.btnClearAll
    val btnSetting = fragmentMainBinding.btnSetting
    val btnToggleAltitude = fragmentMainBinding.btnToggleAltitude
    val sliderAltitude = fragmentMainBinding.sliderElevation
    val btnResetAltitude = fragmentMainBinding.btnResetAltitude
    val sliderModelY = fragmentMainBinding.sliderModely
    val sliderModelZ = fragmentMainBinding.sliderModely
    val btnCapture = fragmentMainBinding.btnCapture
    val btnResetAll = fragmentMainBinding.btnResetAll
    val llInput = fragmentMainBinding.containerInput

    val navController = fragment.findNavController()

    val session get() = fragment.arCoreSessionHelper.session
    val cameraCaptureSession get() = fragment.arCoreSessionHelper.cameraCaptureSession

    val snackbarHelper = SnackbarHelper()

    var mapView: MapView? = null
    val mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
        setup { screenLocation ->
            val latLng: LatLng =
                mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
            fragment.renderer.onMapClick(latLng)
        }
    }

    val mapFragment =
        (fragment.childFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
            it.getMapAsync { googleMap ->
                mapView = MapView(fragment.requireActivity() as MainActivity, googleMap)

            }
        }

    val statusText = root.findViewById<TextView>(R.id.status_text)
    val cameraText = root.findViewById<TextView>(R.id.camera_text)
    val informationText = root.findViewById<TextView>(R.id.information_text);

    fun updateInformationText(text: String) {
        fragment.requireActivity().runOnUiThread {
            informationText.text = text;
        }
    }


    fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
        fragment.requireActivity().runOnUiThread {
            val poseText = if (cameraGeospatialPose == null) "" else
                fragment.requireActivity().getString(
                    R.string.geospatial_pose,
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy
                )
            statusText.text = fragment.requireActivity().resources.getString(
                R.string.earth_state,
                earth.earthState.toString(),
                earth.trackingState.toString(),
                poseText
            )
        }
    }


    fun updateCameraStatusText(camera: Camera) {
        fragment.requireActivity().runOnUiThread {
            cameraText.text = fragment.requireActivity().resources.getString(
                R.string.camera_state,
                camera.trackingState,
                camera.trackingFailureReason.toString(),
                camera.displayOrientedPose.xAxis.toString() + " " + camera.displayOrientedPose.yAxis + " " + camera.displayOrientedPose.zAxis
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
