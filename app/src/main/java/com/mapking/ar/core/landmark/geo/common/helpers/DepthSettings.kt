package com.mapking.ar.core.landmark.geo.common.helpers

import android.content.Context
import android.content.SharedPreferences

class DepthSettings {
    val SHARED_PREFERENCES_ID = "SHARED_PREFERENCES_OCCLUSION_OPTIONS"
    val SHARED_PREFERENCES_SHOW_DEPTH_ENABLE_DIALOG_OOBE = "show_depth_enable_dialog_oobe"
    val SHARED_PREFERENCES_USE_DEPTH_FOR_OCCLUSION = "use_depth_for_occlusion"

    // Current depth-based settings used by the app.
    private var depthColorVisualizationEnabled = false
    private var useDepthForOcclusion = false
    private var sharedPreferences: SharedPreferences? = null

    /** Initializes the current settings based on when the app was last used.  */
    fun onCreate(context: Context) {
        sharedPreferences =
            context.getSharedPreferences(SHARED_PREFERENCES_ID, Context.MODE_PRIVATE)
        useDepthForOcclusion =
            sharedPreferences!!.getBoolean(SHARED_PREFERENCES_USE_DEPTH_FOR_OCCLUSION, false)
    }

    /** Retrieves whether depth-based occlusion is enabled.  */
    fun useDepthForOcclusion(): Boolean {
        return useDepthForOcclusion
    }

    fun setUseDepthForOcclusion(enable: Boolean) {
        if (enable == useDepthForOcclusion) {
            return  // No change.
        }

        // Updates the stored default settings.
        useDepthForOcclusion = enable
        val editor = sharedPreferences!!.edit()
        editor.putBoolean(SHARED_PREFERENCES_USE_DEPTH_FOR_OCCLUSION, useDepthForOcclusion)
        editor.apply()
    }

    /** Retrieves whether to render the depth map visualization instead of the camera feed.  */
    fun depthColorVisualizationEnabled(): Boolean {
        return depthColorVisualizationEnabled
    }

    fun setDepthColorVisualizationEnabled(depthColorVisualizationEnabled: Boolean) {
        this.depthColorVisualizationEnabled = depthColorVisualizationEnabled
    }

    /** Determines if the initial prompt to use depth-based occlusion should be shown.  */
    fun shouldShowDepthEnableDialog(): Boolean {
        // Checks if this dialog has been called before on this device.
        val showDialog =
            sharedPreferences!!.getBoolean(SHARED_PREFERENCES_SHOW_DEPTH_ENABLE_DIALOG_OOBE, true)
        if (showDialog) {
            // Only ever shows the dialog on the first time.  If the user wants to adjust these settings
            // again, they can use the gear icon to invoke the settings menu dialog.
            val editor = sharedPreferences!!.edit()
            editor.putBoolean(SHARED_PREFERENCES_SHOW_DEPTH_ENABLE_DIALOG_OOBE, false)
            editor.apply()
        }
        return showDialog
    }
}