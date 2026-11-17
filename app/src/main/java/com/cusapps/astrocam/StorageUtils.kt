package com.cusapps.astrocam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * StorageUtils handles the complexities of saving media files to the Android system gallery (MediaStore).
 * This ensures that captured images are visible in the Google Photos or Gallery apps immediately.
 */
object StorageUtils {
    private const val TAG = "StorageUtils"

    /**
     * Adds a physical file to the MediaStore so it appears in the device's gallery.
     * This method handles Scoped Storage requirements for Android 10 (API 29) and above.
     * 
     * @param context The application or activity context required to access the ContentResolver.
     * @param file The physical File object sitting in the app's internal or external storage.
     * @param mimeType The type of media (e.g., "image/jpeg").
     * @return The published MediaStore Uri after writing and closing the output stream.
     * @throws IOException If MediaStore cannot create, write, or publish the entry.
     */
    @Throws(IOException::class)
    fun addToMediaStore(context: Context, file: File, mimeType: String): Uri {
        // ContentValues is a name/value pair structure used to insert data into content providers.
        val values = ContentValues().apply {
            // The name of the file as it will appear to the user.
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            // The format of the file.
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            
            // For Android 10+ (Q), we use Scoped Storage properties.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The folder where the file will be logically placed in the gallery.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AstroCam")
                // IS_PENDING=1 tells the system that we are still writing to this file.
                // This prevents other apps from trying to read it before it's ready.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        // The URI (Uniform Resource Identifier) for the public image collection on the device.
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        
        // Insert the metadata into MediaStore and get back a Uri representing the new entry.
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry for ${file.name} in $collection.")
        
        try {
            // Close both streams before making the entry public.
            val outputStream = resolver.openOutputStream(uri)
                ?: throw IOException("Failed to open MediaStore output stream for $uri.")
            outputStream.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            // After writing is done, we must clear the IS_PENDING flag for Android 10+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                val updatedRows = resolver.update(uri, values, null, null)
                if (updatedRows != 1) {
                    throw IOException("Failed to publish MediaStore entry $uri: expected 1 updated row, got $updatedRows.")
                }
            }
            Log.d(TAG, "Successfully added $file to MediaStore.")
            return uri
        } catch (failure: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }
}
