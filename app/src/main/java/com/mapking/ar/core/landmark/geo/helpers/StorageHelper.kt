package com.mapking.ar.core.landmark.geo.helpers

import android.graphics.Bitmap
import com.google.ar.core.GeospatialPose
import com.google.ar.core.ImageMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object StorageHelper {
    /** Utility function to save bitmap. May throw IOException **/
    fun saveBitmap(
        file: File,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ) {
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
    }

    fun saveFloatArray(
        file: File,
        array: FloatArray,
    ) {
        file.bufferedWriter().use { out ->
            array.forEach { v ->
                out.write("$v ")
            }
        }
    }

    fun saveCameraGeospatialPose(file: File, cameraGeospatialPose: GeospatialPose) {
        file.bufferedWriter().use { out ->
            out.write("Latitude ${cameraGeospatialPose.latitude}")
            out.newLine()
            out.write("Longitude ${cameraGeospatialPose.longitude}")
            out.newLine()
            out.write("Horizontal Accuracy ${cameraGeospatialPose.horizontalAccuracy}")
            out.newLine()
            out.write("Altitude ${cameraGeospatialPose.altitude}")
            out.newLine()
            out.write("Vertical Accuracy ${cameraGeospatialPose.verticalAccuracy}")
            out.newLine()
            out.write("Heading ${cameraGeospatialPose.heading}")
            out.newLine()
            out.write("Heading accuracy${cameraGeospatialPose.headingAccuracy}")
        }
    }

    fun saveImageMetaData(
        file: File,
        imageMetadata: ImageMetadata,
    ) {
        file.bufferedWriter().use { out ->
            out.write("What")
        }
    }
}