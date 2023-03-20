package com.mapking.ar.core.landmark.geo.helpers

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mapking.ar.core.landmark.geo.helpers.compass.CompassHelper

class CompassSensorLifecycleHelper(val activity: Activity) : SensorEventListener,
    DefaultLifecycleObserver {
    private val sensorManager by lazy { activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        val accelerometer: Sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        sensorManager.registerListener(
            this, accelerometer,
            SensorManager.SENSOR_DELAY_FASTEST, SensorManager.SENSOR_DELAY_FASTEST
        )


        val magneticField: Sensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorManager.registerListener(
            this, magneticField,
            SensorManager.SENSOR_DELAY_FASTEST, SensorManager.SENSOR_DELAY_FASTEST
        )
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
    }

    companion object {
        private val accelerometerReading = FloatArray(3)
        private val magnetometerReading = FloatArray(3)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                //make sensor readings smoother using a low pass filter
                CompassHelper.lowPassFilter(event.values.clone(), accelerometerReading);
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                //make sensor readings smoother using a low pass filter
                CompassHelper.lowPassFilter(event.values.clone(), magnetometerReading);
            }
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
//        TODO("Not yet implemented")
    }
}