package com.mapking.ar.core.landmark.geo

import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.mapking.ar.core.landmark.geo.databinding.FragmentSharedCameraBinding
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SharedCameraFragment : Fragment(), GLSurfaceView.Renderer,
    ImageReader.OnImageAvailableListener, SurfaceTexture.OnFrameAvailableListener {

    private var _fragmentSharedCameraBinding: FragmentSharedCameraBinding? = null
    private val fragmentSharedCameraBinding get() = _fragmentSharedCameraBinding!!
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _fragmentSharedCameraBinding =
            FragmentSharedCameraBinding.inflate(inflater, container, false)
        return fragmentSharedCameraBinding.root
    }

    companion object {
    }

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        TODO("Not yet implemented")
    }

    override fun onSurfaceChanged(p0: GL10?, p1: Int, p2: Int) {
        TODO("Not yet implemented")
    }

    override fun onDrawFrame(p0: GL10?) {
        TODO("Not yet implemented")
    }

    override fun onImageAvailable(p0: ImageReader?) {
        TODO("Not yet implemented")
    }

    override fun onFrameAvailable(p0: SurfaceTexture?) {
        TODO("Not yet implemented")
    }
}