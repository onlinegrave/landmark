package com.mapking.ar.core.landmark.geo.common.helpers

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CameraPermissionHelper {
    companion object {
        private val CAMERA_PERMISSION_CODE = 0
        private val CAMERA_PERMISSION = Manifest.permission.CAMERA

        /** Check to see we have the necessary permissions for this app.  */
        @JvmStatic fun hasCameraPermission(activity: Activity?): Boolean {
            return (ContextCompat.checkSelfPermission(activity!!, CAMERA_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED)
        }

        /** Check to see we have the necessary permissions for this app, and ask for them if we don't.  */
        @JvmStatic fun requestCameraPermission(activity: Activity?) {
            ActivityCompat.requestPermissions(
                activity!!, arrayOf(CAMERA_PERMISSION), CAMERA_PERMISSION_CODE
            )
        }

        /** Check to see if we need to show the rationale for this permission.  */
        @JvmStatic fun shouldShowRequestPermissionRationale(activity: Activity?): Boolean {
            return ActivityCompat.shouldShowRequestPermissionRationale(
                activity!!,
                CAMERA_PERMISSION
            )
        }

        /** Launch Application Setting to grant permission.  */
        @JvmStatic fun launchPermissionSettings(activity: Activity) {
            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(intent)
        }
    }
}