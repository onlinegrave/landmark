package com.mapking.ar.core.landmark.geo.helpers.computervison

import java.nio.ByteBuffer

/** Image Buffer Class.  */
class TextureReaderImage {
    /** The width of the image, in pixels.  */
    var width: Int

    /** The height of the image, in pixels.  */
    var height: Int

    /** The image buffer.  */
    var buffer: ByteBuffer

    /** Pixel format. Can be either IMAGE_FORMAT_RGBA or IMAGE_FORMAT_I8.  */
    var format: Int

    /** Default constructor.  */
    constructor() {
        width = 1
        height = 1
        format = IMAGE_FORMAT_RGBA
        buffer = ByteBuffer.allocateDirect(4)
    }

    /**
     * Constructor.
     *
     * @param imgWidth the width of the image, in pixels.
     * @param imgHeight the height of the image, in pixels.
     * @param imgFormat the format of the image.
     * @param imgBuffer the buffer of the image pixels.
     */
    constructor(imgWidth: Int, imgHeight: Int, imgFormat: Int, imgBuffer: ByteBuffer?) {
        if (imgWidth == 0 || imgHeight == 0) {
            throw RuntimeException("Invalid image size.")
        }
        if (imgFormat != IMAGE_FORMAT_RGBA && imgFormat != IMAGE_FORMAT_I8) {
            throw RuntimeException("Invalid image format.")
        }
        if (imgBuffer == null) {
            throw RuntimeException("Pixel buffer cannot be null.")
        }
        width = imgWidth
        height = imgHeight
        format = imgFormat
        buffer = imgBuffer
    }

    companion object {
        /** The id corresponding to RGBA8888.  */
        const val IMAGE_FORMAT_RGBA = 0

        /** The id corresponding to grayscale.  */
        const val IMAGE_FORMAT_I8 = 1
    }
}
