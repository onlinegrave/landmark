package com.mapking.ar.core.landmark.geo

import android.app.Application
import android.content.pm.PackageManager
import android.opengl.ETC1Util
import android.opengl.GLES10
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

class LandmarkApplication: Application() {
    companion object {
        private const val TAG = "LandmarkApplication"
    }

    override fun onCreate() {
        super.onCreate()
        if(ETC1Util.isETC1Supported()) {
        }

        var deviceSupportsAEP: Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK)
        var extensions = GLES31.glGetString(GL10.GL_EXTENSIONS)
        GLES31.glGetString(GLES31.GL_VERSION).also {
            Log.i(TAG, "onCreate: $it")
        }
        GLES31.glGetString(GL10.GL_VERSION).also {
            Log.i(TAG, "onCreate: $it")
        }
        ContextFactory()
    }
}

private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
private const val glVersion = 3.0
private class ContextFactory : GLSurfaceView.EGLContextFactory {

    override fun createContext(egl: EGL10, display: EGLDisplay, eglConfig: EGLConfig): EGLContext {

        Log.w("LandmarkApplication", "creating OpenGL ES $glVersion context")
        return egl.eglCreateContext(
            display,
            eglConfig,
            EGL10.EGL_NO_CONTEXT,
            intArrayOf(EGL_CONTEXT_CLIENT_VERSION, glVersion.toInt(), EGL10.EGL_NONE)
        ) // returns null if 3.0 is not supported
    }

    override fun destroyContext(egl: EGL10?, display: EGLDisplay?, context: EGLContext?) {
    }
}