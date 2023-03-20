package com.mapking.ar.core.landmark.geo

import android.graphics.*
import androidx.annotation.ColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.*

class SettingMapView(val activity: SettingActivity, val googleMap: GoogleMap) {
    private val CAMERA_MARKER_COLOR: Int = Color.argb(255, 0, 255, 0)
    private val EARTH_MARKER_COLOR: Int = Color.argb(255, 125, 125, 125)

    var setInitialCameraPosition = false
    val cameraMarker = createMarker(CAMERA_MARKER_COLOR)
    var cameraIdle = true

    val earthMarker = createMarker(EARTH_MARKER_COLOR)

    init {
        googleMap.uiSettings.apply {
            isMapToolbarEnabled = false
            isIndoorLevelPickerEnabled = false
            isZoomControlsEnabled = false
            isTiltGesturesEnabled = false
            isScrollGesturesEnabled = false
        }

        googleMap.setOnMarkerClickListener { unused -> false }

        // Add listeners to keep track of when the GoogleMap camera is moving.
        googleMap.setOnCameraMoveListener { cameraIdle = false }
        googleMap.setOnCameraIdleListener { cameraIdle = true }
    }

    fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
        val position = LatLng(latitude, longitude)
        activity.runOnUiThread {
            // If the map is already in the process of a camera update, then don't move it.
            if (!cameraIdle) {
                return@runOnUiThread
            }
            cameraMarker.isVisible = true
            cameraMarker.position = position
            cameraMarker.rotation = heading.toFloat()

            val cameraPositionBuilder: CameraPosition.Builder = if (!setInitialCameraPosition) {
                // Set the camera position with an initial default zoom level.
                setInitialCameraPosition = true
                CameraPosition.Builder().zoom(21f).target(position)
            } else {
                // Set the camera position and keep the same zoom level.
                CameraPosition.Builder()
                    .zoom(googleMap.cameraPosition.zoom)
                    .target(position)
            }
            googleMap.moveCamera(
                CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build())
            )
        }
    }

    /** Creates and adds a 2D anchor marker on the 2D map view.  */
    private fun createMarker(
        color: Int,
    ): Marker {
        val markersOptions = MarkerOptions()
            .position(LatLng(0.0, 0.0))
            .draggable(false)
            .anchor(0.5f, 0.5f)
            .flat(true)
            .visible(false)
            .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(color)))
        return googleMap.addMarker(markersOptions)!!
    }

    private fun createColoredMarkerBitmap(@ColorInt color: Int): Bitmap {
        val opt = BitmapFactory.Options()
        opt.inMutable = true
        val navigationIcon =
            BitmapFactory.decodeResource(
                activity.resources,
                R.drawable.ic_navigation_white_48dp,
                opt
            )
        val p = Paint()
        p.colorFilter = LightingColorFilter(color,  /* add= */1)
        val canvas = Canvas(navigationIcon)
        canvas.drawBitmap(navigationIcon,  /* left= */0f,  /* top= */0f, p)
        return navigationIcon
    }
}