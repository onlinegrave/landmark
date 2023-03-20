package com.mapking.ar.core.landmark.geo.helpers.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.*
import java.nio.ByteBuffer

/**
 * Helper class used to convert a [Image] object from
 * [ImageFormat.YUV_420_888] format to an RGB [Bitmap] object, it has equivalent
 * functionality to https://github
 * .com/androidx/androidx/blob/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageYuvToRgbConverter.java
 *
 * NOTE: This has been tested in a limited number of devices and is not
 * considered production-ready code. It was created for illustration purposes,
 * since this is not an efficient camera pipeline due to the multiple copies
 * required to convert each frame. For example, this
 * implementation
 * (https://stackoverflow.com/questions/52726002/camera2-captured-picture-conversion-from-yuv-420-888-to-nv21/52740776#52740776)
 * might have better performance.
 * See YUV_420_888toNV21 for more details
 */
class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb =
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    // Do not add getters/setters functions to these private variables
    // because yuvToRgb() assume they won't be modified elsewhere
    private var yuvBits: ByteBuffer? = null
    private var bytes: ByteArray = ByteArray(0)
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null

    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        val yuvBuffer = YuvByteBuffer(image, yuvBits)
        yuvBits = yuvBuffer.buffer

        if (needCreateAllocations(image, yuvBuffer)) {
            val yuvType = Type.Builder(rs, Element.U8(rs))
                .setX(image.width)
                .setY(image.height)
                .setYuvFormat(yuvBuffer.type)
            inputAllocation = Allocation.createTyped(
                rs,
                yuvType.create(),
                Allocation.USAGE_SCRIPT
            )
            bytes = ByteArray(yuvBuffer.buffer.capacity())
            val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(image.width)
                .setY(image.height)
            outputAllocation = Allocation.createTyped(
                rs,
                rgbaType.create(),
                Allocation.USAGE_SCRIPT
            )
        }

        yuvBuffer.buffer.get(bytes)
        inputAllocation!!.copyFrom(bytes)

        // Convert NV21 or YUV_420_888 format to RGB
        inputAllocation!!.copyFrom(bytes)
        scriptYuvToRgb.setInput(inputAllocation)
        scriptYuvToRgb.forEach(outputAllocation)
        outputAllocation!!.copyTo(output)
    }

    private fun needCreateAllocations(image: Image, yuvBuffer: YuvByteBuffer): Boolean {
        return (inputAllocation == null ||               // the very 1st call
                inputAllocation!!.type.x != image.width ||   // image size changed
                inputAllocation!!.type.y != image.height ||
                inputAllocation!!.type.yuv != yuvBuffer.type || // image format changed
                bytes.size == yuvBuffer.buffer.capacity())
    }

    /** Your code will only return correct NV21 if there is no padding at all, and U and V plains overlap and actually represent interlaced VU values. This happens quite often for preview, but in such case you allocate extra w*h/4 bytes for your array (which presumably is not a problem). Maybe for captured image you need a more robust implemenation, e.g. **/
    /** Solution copied from https://stackoverflow.com/questions/52726002/camera2-captured-picture-conversion-from-yuv-420-888-to-nv21/52740776#52740776. Maybe faster but not tested **/
//    private fun YUV_420_888toNV21(image: Image): ByteArray {
//        val width = image.width
//        val height = image.height
//        val ySize = width*height
//        val uvSize = width*height/4
//
//        val nv21 = ByteArray(ySize + uvSize*2);
//
//        val yBuffer: ByteBuffer = image.planes[0].buffer // Y
//        val uBuffer: ByteBuffer = image.planes[1].buffer // U
//        val vBuffer: ByteBuffer = image.planes[2].buffer // V
//
//        val rowStride = image.planes[0].rowStride
//        assert(image.planes[0].pixelStride == 1)
//
//        var pos = 0
//
//        if (rowStride == width) { // likely
//            yBuffer.get(nv21, 0, ySize);
//            pos += ySize;
//        }
//        else {
//            val yBufferPos = -rowStride // not an actual position
//            for (; pos<ySize; pos+=width) {
//                yBufferPos += rowStride;
//                yBuffer.position(yBufferPos);
//                yBuffer.get(nv21, pos, width);
//            }
//        }
//
//        rowStride = image.getPlanes()[2].getRowStride();
//        int pixelStride = image.getPlanes()[2].getPixelStride();
//
//        assert(rowStride == image.getPlanes()[1].getRowStride());
//        assert(pixelStride == image.getPlanes()[1].getPixelStride());
//
//        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
//            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
//            byte savePixel = vBuffer.get(1);
//            try {
//                vBuffer.put(1, (byte)~savePixel);
//                if (uBuffer.get(0) == (byte)~savePixel) {
//                    vBuffer.put(1, savePixel);
//                    vBuffer.position(0);
//                    uBuffer.position(0);
//                    vBuffer.get(nv21, ySize, 1);
//                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());
//
//                    return nv21; // shortcut
//                }
//            }
//            catch (ReadOnlyBufferException ex) {
//                // unfortunately, we cannot check if vBuffer and uBuffer overlap
//            }
//
//            // unfortunately, the check failed. We must save U and V pixel by pixel
//            vBuffer.put(1, savePixel);
//        }
//
//        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
//        // but performance gain would be less significant
//
//        for (int row=0; row<height/2; row++) {
//            for (int col=0; col<width/2; col++) {
//            int vuPos = col*pixelStride + row*rowStride;
//            nv21[pos++] = vBuffer.get(vuPos);
//            nv21[pos++] = uBuffer.get(vuPos);
//        }
//        }
//
//        return nv21;
//    }
}
