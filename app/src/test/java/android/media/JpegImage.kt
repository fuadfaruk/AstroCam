package android.media

import android.graphics.ImageFormat
import java.nio.ByteBuffer

// Image and Plane have package-private constructors in the Android SDK test stubs.
class JpegImage(private val bytes: ByteArray) : Image() {
    var closed = false

    override fun getFormat(): Int = ImageFormat.JPEG
    override fun getWidth(): Int = 1
    override fun getHeight(): Int = 1
    override fun getTimestamp(): Long = 1L
    override fun getPlanes(): Array<Plane> = arrayOf(object : Plane() {
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        override fun getPixelStride(): Int = 1
        override fun getRowStride(): Int = bytes.size
    })

    override fun close() {
        closed = true
    }
}
