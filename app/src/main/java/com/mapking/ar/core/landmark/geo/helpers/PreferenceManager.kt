package com.mapking.ar.core.landmark.geo.helpers

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.location.LocationRequest
import com.google.ar.core.Config
import java.util.*


/** Singleton manager to manage all saved data in preference. Should not store sensitive data. **/
class PreferenceManager private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences

    var arGeospatialMode: Config.GeospatialMode
        get() {
            return Config.GeospatialMode.ENABLED
        }
        set(mode) {
        }


    var arFocusMode: Config.FocusMode
        get() {
            return Config.FocusMode.FIXED
        }
        set(mode) {
        }


    private fun setString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    private fun setInteger(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }

    private fun setBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    private fun setLong(key: String, value: Long) {
        sharedPreferences.edit().putLong(key, value).apply()
    }

    companion object {
        const val KEY_AR_GEOSPATIAL_MODE = "com.google.ar.core.Config.GeospatialMode"
        const val KEY_AR_AUGMENTED_FACE_MODE = "com.google.ar.core.Config.AugmentedFaceMode"
        const val KEY_AR_CLOUD_ANCHOR_MODE = "com.google.ar.core.Config.CloudAnchorMode"
        const val KEY_AR_DEPTH_MODE = "com.google.ar.core.Config.DepthMode"
        const val KEY_AR_FOCUS_MODE = "com.google.ar.core.Config.FocusMode"
        const val KEY_AR_INSTANT_PLACEMENT_MODE = "com.google.ar.core.Config.InstantPlacementMode"
        const val KEY_AR_LIGHT_ESTIMATION_MODE = "com.google.ar.core.Config.LightEstimationMode"
        const val KEY_AR_PLANE_FINDING_MODE = "com.google.ar.core.Config.PlaneFindingMode"
        const val KEY_AR_UPDATE_MODE = "com.google.ar.core.Config.UpdateMode"

        private var INSTANCE: PreferenceManager? = null
        const val mFileName: String = "com.mapking.ar.core.landmark.geo.helpers.PreferenceManager"

        fun getInstance(context: Context): PreferenceManager? {
            if (INSTANCE == null) {
                synchronized(PreferenceManager::class.java) {
                    INSTANCE = PreferenceManager(context)
                }
            }
            return INSTANCE
        }
    }

    init {
        sharedPreferences = context.getSharedPreferences(mFileName, Context.MODE_PRIVATE)
    }
}
