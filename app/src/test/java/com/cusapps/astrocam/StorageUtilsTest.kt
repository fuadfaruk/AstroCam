package com.cusapps.astrocam

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [StorageUtilsTest.RecordingContentResolver::class])
class StorageUtilsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var resolver: RecordingContentResolver
    private lateinit var output: RecordingOutputStream
    private lateinit var file: File
    private val bytes = byteArrayOf(0, 1, 127, -128, -1)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        resolver = Shadow.extract(context.contentResolver)
        output = RecordingOutputStream(resolver.events)
        resolver.outputStream = output
        file = temporaryFolder.newFile("capture.jpg").apply { writeBytes(bytes) }
    }

    @Test
    fun `null insert throws without opening or deleting a row`() {
        resolver.insertedUri = null

        val failure = assertThrows(IOException::class.java) { save() }

        assertTrue(failure.message!!.contains(file.name))
        assertTrue(failure.message!!.contains("MediaStore"))
        assertEquals(listOf("insert"), resolver.events)
        assertTrue(resolver.deletedEntries.isEmpty())
    }

    @Test
    fun `insert exception propagates without trying rollback`() {
        val original = SecurityException("insert denied")
        resolver.insertFailure = original

        assertSame(original, assertThrows(SecurityException::class.java) { save() })
        assertEquals(listOf("insert"), resolver.events)
        assertTrue(resolver.deletedEntries.isEmpty())
    }

    @Test
    fun `null output stream throws and deletes the inserted row`() {
        resolver.outputStream = null

        val failure = assertThrows(IOException::class.java) { save() }

        assertTrue(failure.message!!.contains(resolver.targetUri.toString()))
        assertTrue(failure.message!!.contains("output stream"))
        assertEquals(listOf("insert", "open", "delete"), resolver.events)
        assertRolledBack()
    }

    @Test
    fun `opening output stream failure propagates and rolls back`() {
        val original = FileNotFoundException("cannot open gallery file")
        resolver.openFailure = original

        assertSame(original, assertThrows(FileNotFoundException::class.java) { save() })
        assertEquals(listOf("insert", "open", "delete"), resolver.events)
        assertRolledBack()
    }

    @Test
    fun `write failure closes output and rolls back without publishing`() {
        val original = IOException("gallery write failed")
        output.writeFailure = original

        assertSame(original, assertThrows(IOException::class.java) { save() })
        assertEquals(listOf("insert", "open", "write", "close", "delete"), resolver.events)
        assertTrue(output.closed)
        assertRolledBack()
    }

    @Test
    fun `close failure rolls back without publishing`() {
        val original = IOException("gallery close failed")
        output.closeFailure = original

        assertSame(original, assertThrows(IOException::class.java) { save() })
        assertEquals(listOf("insert", "open", "write", "close", "delete"), resolver.events)
        assertArrayEquals(bytes, output.bytes.toByteArray())
        assertRolledBack()
    }

    @Test
    fun `missing source file closes output and rolls back`() {
        val missingFile = File(temporaryFolder.root, "missing.jpg")

        assertThrows(FileNotFoundException::class.java) {
            StorageUtils.addToMediaStore(context, missingFile, "image/jpeg")
        }

        assertEquals(listOf("insert", "open", "close", "delete"), resolver.events)
        assertTrue(output.closed)
        assertRolledBack()
    }

    @Test
    fun `finalization exception propagates after close and rolls back`() {
        val original = SecurityException("publish denied")
        resolver.updateFailure = original

        assertSame(original, assertThrows(SecurityException::class.java) { save() })
        assertEquals(listOf("insert", "open", "write", "close", "update", "delete"), resolver.events)
        assertTrue(output.closed)
        assertEquals(listOf(resolver.targetUri), resolver.deletedEntries)
    }

    @Test
    fun `zero updated rows fails publication and rolls back`() {
        resolver.updatedRows = 0

        val failure = assertThrows(IOException::class.java) { save() }

        assertTrue(failure.message!!.contains(resolver.targetUri.toString()))
        assertTrue(failure.message!!.contains("got 0"))
        assertEquals(listOf("insert", "open", "write", "close", "update", "delete"), resolver.events)
        assertEquals(listOf(resolver.targetUri), resolver.deletedEntries)
    }

    @Test
    fun `multiple updated rows also fails publication and rolls back`() {
        resolver.updatedRows = 2

        val failure = assertThrows(IOException::class.java) { save() }

        assertTrue(failure.message!!.contains("got 2"))
        assertEquals(listOf("insert", "open", "write", "close", "update", "delete"), resolver.events)
        assertEquals(listOf(resolver.targetUri), resolver.deletedEntries)
    }

    @Test
    fun `success writes and closes before publishing and returning the inserted Uri`() {
        val publishedUri = save()
        resolver.events += "return"

        assertSame(resolver.targetUri, publishedUri)
        assertEquals(listOf("insert", "open", "write", "close", "update", "return"), resolver.events)
        assertArrayEquals(bytes, output.bytes.toByteArray())
        assertTrue(output.closed)
        assertTrue(resolver.deletedEntries.isEmpty())
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, resolver.collectionUri)
        assertEquals(resolver.targetUri, resolver.openedUri)
        assertEquals(resolver.targetUri, resolver.updatedUri)
        assertEquals(file.name, resolver.insertValues!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", resolver.insertValues!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/AstroCam", resolver.insertValues!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, resolver.insertValues!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1, resolver.updateValues!!.size())
        assertEquals(0, resolver.updateValues!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun `rollback exception is suppressed without replacing the write failure`() {
        val original = IOException("gallery write failed")
        val cleanupFailure = SecurityException("delete denied")
        output.writeFailure = original
        resolver.deleteFailure = cleanupFailure

        val failure = assertThrows(IOException::class.java) { save() }

        assertSame(original, failure)
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertRolledBack()
    }

    @Test
    fun `write failure stays primary when both close and rollback also fail`() {
        val original = IOException("gallery write failed")
        val closeFailure = IOException("gallery close failed")
        val cleanupFailure = SecurityException("delete denied")
        output.writeFailure = original
        output.closeFailure = closeFailure
        resolver.deleteFailure = cleanupFailure

        val failure = assertThrows(IOException::class.java) { save() }

        assertSame(original, failure)
        assertEquals(listOf(closeFailure, cleanupFailure), failure.suppressed.toList())
        assertRolledBack()
    }

    @Test
    fun `rollback rethrowing the same exception still preserves the original`() {
        val original = IOException("provider failed")
        resolver.openFailure = original
        resolver.deleteFailure = original

        assertSame(original, assertThrows(IOException::class.java) { save() })
        assertRolledBack()
    }

    @Test
    @Config(sdk = [28])
    fun `API28 success writes and closes without scoped storage columns or finalization`() {
        val publishedUri = save()

        assertSame(resolver.targetUri, publishedUri)
        assertEquals(listOf("insert", "open", "write", "close"), resolver.events)
        assertArrayEquals(bytes, output.bytes.toByteArray())
        assertTrue(output.closed)
        assertTrue(resolver.deletedEntries.isEmpty())
        assertEquals(2, resolver.insertValues!!.size())
        assertEquals(file.name, resolver.insertValues!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", resolver.insertValues!!.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertFalse(resolver.insertValues!!.containsKey(MediaStore.MediaColumns.RELATIVE_PATH))
        assertFalse(resolver.insertValues!!.containsKey(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    @Config(sdk = [28])
    fun `API28 write failure also closes output and deletes the incomplete row`() {
        val original = IOException("legacy gallery write failed")
        output.writeFailure = original

        assertSame(original, assertThrows(IOException::class.java) { save() })
        assertEquals(listOf("insert", "open", "write", "close", "delete"), resolver.events)
        assertTrue(output.closed)
        assertRolledBack()
        assertFalse(resolver.insertValues!!.containsKey(MediaStore.MediaColumns.RELATIVE_PATH))
        assertFalse(resolver.insertValues!!.containsKey(MediaStore.MediaColumns.IS_PENDING))
    }

    private fun save(): Uri = StorageUtils.addToMediaStore(context, file, "image/jpeg")

    private fun assertRolledBack() {
        assertEquals(listOf(resolver.targetUri), resolver.deletedEntries)
        assertFalse(resolver.events.contains("update"))
    }

    @Implements(ContentResolver::class)
    class RecordingContentResolver : ShadowContentResolver() {
        val events = mutableListOf<String>()
        val targetUri: Uri = Uri.parse("content://media/external/images/media/42")
        var insertedUri: Uri? = targetUri
        var outputStream: OutputStream? = null
        var insertFailure: Throwable? = null
        var openFailure: Throwable? = null
        var updateFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var updatedRows = 1
        var collectionUri: Uri? = null
        var openedUri: Uri? = null
        var updatedUri: Uri? = null
        var insertValues: ContentValues? = null
        var updateValues: ContentValues? = null
        val deletedEntries = mutableListOf<Uri>()

        @Implementation
        override fun insert(url: Uri, values: ContentValues): Uri? {
            events += "insert"
            collectionUri = url
            insertValues = ContentValues(values)
            insertFailure?.let { throw it }
            return insertedUri
        }

        @Implementation
        override fun openOutputStream(uri: Uri): OutputStream? {
            events += "open"
            openedUri = uri
            openFailure?.let { throw it }
            return outputStream
        }

        @Implementation
        override fun update(uri: Uri, values: ContentValues, where: String?, selectionArgs: Array<String>?): Int {
            events += "update"
            updatedUri = uri
            updateValues = ContentValues(values)
            updateFailure?.let { throw it }
            return updatedRows
        }

        @Implementation
        override fun delete(url: Uri, where: String?, selectionArgs: Array<String>?): Int {
            events += "delete"
            deletedEntries += url
            deleteFailure?.let { throw it }
            return 1
        }
    }

    private class RecordingOutputStream(private val events: MutableList<String>) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writeFailure: IOException? = null
        var closeFailure: IOException? = null
        var closed = false

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            events += "write"
            writeFailure?.let { throw it }
            bytes.write(buffer, offset, length)
        }

        override fun close() {
            events += "close"
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
