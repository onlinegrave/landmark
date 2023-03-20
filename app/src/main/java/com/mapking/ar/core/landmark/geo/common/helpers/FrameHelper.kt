package com.mapking.ar.core.landmark.geo.common.helpers

import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata

class FrameHelper {
    fun getSensorExposureTime(frame: Frame): Long? {
        return runCatching {
            // Can throw NotYetAvailableException when sensors data is not yet available.
            val metadata = frame.imageMetadata

            // Get the exposure time metadata. Throws MetadataNotFoundException if it's not available.
            return metadata.getLong(ImageMetadata.SENSOR_EXPOSURE_TIME)
        }
            .getOrNull()
    }
}