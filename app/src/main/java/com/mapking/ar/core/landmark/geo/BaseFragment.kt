package com.mapking.ar.core.landmark.geo

import android.content.Context
import androidx.fragment.app.Fragment
import java.lang.Exception

abstract class BaseFragment : LogFragment() {
    lateinit var mainActivity: MainActivity

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is MainActivity) {
            throw Exception("Can only be instance of HelloGeoActivity")
        }
        mainActivity = context as MainActivity
    }
}