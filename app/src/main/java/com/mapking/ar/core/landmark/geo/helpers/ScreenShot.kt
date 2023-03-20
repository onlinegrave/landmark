package com.mapking.ar.core.landmark.geo.helpers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat.startActivity
import java.io.File


object ScreenShot {
    fun takeScreenshot1() {
//        val now = Date()
//        DateFormat.format("yyyy-MM-dd_hh:mm:ss", now)
//        try {
//            val mPath: String =
//                Environment.getExternalStorageDirectory().toString().toString() + "/" + now + ".jpg"
//
//            val v1: View = JSObject.getWindow().getDecorView().getRootView()
//            v1.setDrawingCacheEnabled(true)
//            val bitmap: Bitmap = Bitmap.createBitmap(v1.getDrawingCache())
//            v1.setDrawingCacheEnabled(false)
//            val imageFile = File(mPath)
//            val outputStream = FileOutputStream(imageFile)
//            val quality = 100
//            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
//            outputStream.flush()
//            outputStream.close()
//        } catch (e: Throwable) {
//            // Several error may come out with file handling or DOM
//            e.printStackTrace()
//        }
    }

    fun takeScreenshot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap;
    }


//    fun openScreenshot(activity: Context, imageFile: File) {
//        val intent = Intent()
//        intent.action = Intent.ACTION_VIEW
//        val uri: Uri = Uri.fromFile(imageFile)
//        intent.setDataAndType(uri, "image/*")
//        startActivity(intent)
//    }
}