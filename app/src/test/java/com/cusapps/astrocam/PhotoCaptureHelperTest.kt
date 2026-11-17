package com.cusapps.astrocam

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.media.JpegImage
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PhotoCaptureHelperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var stagingDirectory: File
    private lateinit var context: ContextWrapper
    private lateinit var provider: GalleryProvider

    @Before
    fun setUp() {
        stagingDirectory = temporaryFolder.newFolder("staging")
        val application = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(application) {
            override fun getExternalFilesDir(type: String?): File = stagingDirectory
        }
        provider = GalleryProvider(temporaryFolder.newFile("gallery.jpg"))
        provider.attachInfo(application, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test
    fun `success receives published URI after closing image and deleting staging file`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val image = JpegImage(bytes)
        var saved = 0
        val errors = mutableListOf<Exception>()

        PhotoCaptureHelper.saveImage(context, image,
            onImageSaved = { uri ->
                saved++
                assertEquals(provider.uri, uri)
                assertTrue(image.closed)
                assertTrue(stagingDirectory.listFiles()!!.isEmpty())
                assertEquals(listOf("insert", "open", "publish"), provider.events)
                assertArrayEquals(bytes, provider.galleryFile.readBytes())
            },
            onError = { errors.add(it) }
        )

        assertEquals(1, saved)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `null insert reports only an error and cleans staging file and image`() {
        provider.nullInsert = true
        assertSaveFailsAndCleansUp(listOf("insert"))
    }

    @Test
    fun `null output stream reports error and removes incomplete gallery entry`() {
        provider.nullStream = true
        assertSaveFailsAndCleansUp(listOf("insert", "open", "delete"))
    }

    @Test
    fun `failed publication reaches error callback and removes the gallery entry`() {
        val error = IllegalStateException("Gallery publication failed")
        provider.publishError = error
        val actual = assertSaveFailsAndCleansUp(listOf("insert", "open", "publish", "delete"))

        assertSame(error, actual)
    }

    @Test
    fun `staging directory failure reaches error callback and still closes image`() {
        val error = SecurityException("Storage unavailable")
        val failingContext = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = throw error
        }
        val image = JpegImage(byteArrayOf(1))
        val errors = mutableListOf<Exception>()
        var saved = 0

        PhotoCaptureHelper.saveImage(failingContext, image,
            onImageSaved = { saved++ },
            onError = { errors.add(it) }
        )

        assertEquals(0, saved)
        assertSame(error, errors.single())
        assertTrue(image.closed)
        assertTrue(provider.events.isEmpty())
        assertTrue(stagingDirectory.listFiles()!!.isEmpty())
    }

    @Test
    fun `success callback exceptions are not turned into a second save outcome`() {
        val error = IllegalStateException("Callback failed")
        val image = JpegImage(byteArrayOf(1))
        var errors = 0

        val actual = assertThrows(IllegalStateException::class.java) {
            PhotoCaptureHelper.saveImage(context, image,
                onImageSaved = { throw error },
                onError = { errors++ }
            )
        }

        assertSame(error, actual)
        assertEquals(0, errors)
        assertTrue(image.closed)
        assertTrue(stagingDirectory.listFiles()!!.isEmpty())
    }

    private fun assertSaveFailsAndCleansUp(expectedEvents: List<String>): Exception {
        val image = JpegImage(byteArrayOf(1, 2, 3))
        var saved = 0
        val errors = mutableListOf<Exception>()

        PhotoCaptureHelper.saveImage(context, image,
            onImageSaved = { saved++ },
            onError = { errors.add(it) }
        )

        assertEquals(0, saved)
        assertEquals(1, errors.size)
        assertTrue(image.closed)
        assertTrue(stagingDirectory.listFiles()!!.isEmpty())
        assertEquals(expectedEvents, provider.events)
        return errors.single()
    }

    private class GalleryProvider(val galleryFile: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/images/media/1")
        val events = mutableListOf<String>()
        var nullInsert = false
        var nullStream = false
        var publishError: Exception? = null

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            events.add("insert")
            return if (nullInsert) null else this.uri
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            events.add("open")
            if (nullStream) return null
            val descriptor = ParcelFileDescriptor.open(galleryFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
            return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            events.add("publish")
            publishError?.let { throw it }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            events.add("delete")
            galleryFile.delete()
            return 1
        }
    }
}
