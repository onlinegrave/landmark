package com.mapking.ar.core.landmark.geo.helpers.computervison

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Helper to measure frame-to-frame timing and frame rate. */
class FrameTimeHelper : DefaultLifecycleObserver {
    // System time of last frame, or zero if no time has been recorded.
    private var previousFrameTime: Long = 0

    /** Determine smoothed frame-to-frame time. Returns zero if frame time cannot be determined.  */
    // Smoothed frame time, or zero if frame time has not yet been recorded.
    var smoothedFrameTime = 0f
        private set

    override fun onResume(owner: LifecycleOwner) {
        // Reset timing data during initialization and after app pause.
        previousFrameTime = 0
        smoothedFrameTime = 0f
    }

    /** Capture current frame timestamp and calculate smoothed frame-to-frame time.  */
    fun nextFrame() {
        val now = System.currentTimeMillis()

        // Is nextFrame() being called for the first time?
        if (previousFrameTime == 0L) {
            previousFrameTime = now

            // Unable to calculate frame time based on single timestamp.
            smoothedFrameTime = 0f
            return
        }

        // Determine momentary frame-to-frame time.
        val frameTime = now - previousFrameTime

        // Use current frame time as previous frame time during next invocation.
        previousFrameTime = now

        // Is nextFrame() being called for the second time, in which case we have only one measurement.
        if (smoothedFrameTime == 0f) {
            smoothedFrameTime = frameTime.toFloat()
            return
        }

        // In all subsequent calls to nextFrame(), calculated a smoothed frame rate.
        smoothedFrameTime += SMOOTHING_FACTOR * (frameTime - smoothedFrameTime)
    }

    /** Determine smoothed frame rate. Returns zero if frame rate cannot be determined.  */
    val smoothedFrameRate: Float
        get() = if (smoothedFrameTime == 0f) 0f else MILLISECONDS_PER_SECOND / smoothedFrameTime

    companion object {
        // Number of milliseconds in one second.
        private const val MILLISECONDS_PER_SECOND = 1000f

        // Rate by which smoothed frame rate should approach momentary frame rate.
        private const val SMOOTHING_FACTOR = .03f
    }
}
