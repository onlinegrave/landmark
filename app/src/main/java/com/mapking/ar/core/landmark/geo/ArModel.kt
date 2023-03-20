package com.mapking.ar.core.landmark.geo

import com.google.ar.core.Anchor
import com.mapking.ar.core.landmark.geo.common.samplerender.Mesh
import com.mapking.ar.core.landmark.geo.common.samplerender.SampleRender
import com.mapking.ar.core.landmark.geo.common.samplerender.Shader
import com.mapking.ar.core.landmark.geo.common.samplerender.Texture

class ArModel(
    val virtualObjectMesh: Mesh,
    val virtualObjectShader: Shader,
    val virtualObjectTexture: Texture,
    val config: AnchorConfig
)
