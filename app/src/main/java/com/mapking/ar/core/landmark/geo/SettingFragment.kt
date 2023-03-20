package com.mapking.ar.core.landmark.geo

import android.os.Bundle
import android.preference.PreferenceFragment
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import com.mapking.ar.core.landmark.geo.databinding.FragmentSettingBinding

class SettingFragment : Fragment() {
    private var _fragmentSettingBinding: FragmentSettingBinding? = null

    private val fragmentSettingBinding get() = _fragmentSettingBinding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentSettingBinding = FragmentSettingBinding.inflate(inflater, container, false)
        return fragmentSettingBinding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentSettingBinding = null
    }


    companion object {
    }
}