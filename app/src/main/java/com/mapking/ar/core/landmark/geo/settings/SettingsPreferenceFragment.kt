package com.mapking.ar.core.landmark.geo.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.mapking.ar.core.landmark.geo.R

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }
}