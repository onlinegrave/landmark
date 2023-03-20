package com.mapking.ar.core.landmark.geo.helpers

import android.content.Context
import android.content.SharedPreferences

class InstantPlacementSettings {
    val SHARED_PREFERENCES_ID = "SHARED_PREFERENCES_INSTANT_PLACEMENT_OPTIONS"
    val SHARED_PREFERENCES_INSTANT_PLACEMENT_ENABLED = "instant_placement_enabled"
    private var instantPlacementEnabled = true
    private var sharedPreferences: SharedPreferences? = null

    /** Initializes the current settings based on the saved value.  */
    fun onCreate(context: Context) {
        sharedPreferences =
            context.getSharedPreferences(SHARED_PREFERENCES_ID, Context.MODE_PRIVATE)
        instantPlacementEnabled =
            sharedPreferences!!.getBoolean(SHARED_PREFERENCES_INSTANT_PLACEMENT_ENABLED, false)
    }

    /** Retrieves whether Instant Placement is enabled,  */
    fun isInstantPlacementEnabled(): Boolean {
        return instantPlacementEnabled
    }

    fun setInstantPlacementEnabled(enable: Boolean) {
        if (enable == instantPlacementEnabled) {
            return  // No change.
        }

        // Updates the stored default settings.
        instantPlacementEnabled = enable
        val editor = sharedPreferences!!.edit()
        editor.putBoolean(SHARED_PREFERENCES_INSTANT_PLACEMENT_ENABLED, instantPlacementEnabled)
        editor.apply()
    }
}