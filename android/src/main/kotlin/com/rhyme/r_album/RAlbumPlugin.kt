package com.rhyme.r_album

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

const val methodName: String = "com.rhyme_lph/r_album"
var context: Context? = null

class RAlbumPlugin : FlutterPlugin, MethodCallHandler {
    // Use the main thread handler.
    private val handler: Handler = Handler(Looper.getMainLooper())
    
    // Helper enum for media types
    enum class MediaType {
        IMAGE, VIDEO, AUDIO, UNKNOWN
    }
    
    // Helper function to detect actual media type using MediaMetadataRetriever
    private fun detectMediaType(filePath: String): MediaType {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            
            // Check if it has video tracks
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            // Check if it has audio tracks
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            // Get MIME type
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            
            when {
                hasVideo == "yes" -> MediaType.VIDEO
                hasAudio == "yes" && (mimeType?.startsWith("audio/") == true || hasVideo != "yes") -> MediaType.AUDIO
                mimeType?.startsWith("image/") == true -> MediaType.IMAGE
                else -> {
                    // Fallback to extension-based detection for images
                    val extension = filePath.substringAfterLast(".").lowercase(java.util.Locale.ROOT)
                    val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
                    if (imageExtensions.contains(extension)) MediaType.IMAGE else MediaType.UNKNOWN
                }
            }
        } catch (e: Exception) {
            // If MediaMetadataRetriever fails, fallback to extension-based detection
            val extension = filePath.substringAfterLast(".").lowercase(java.util.Locale.ROOT)
            when {
                listOf("mp4", "3gp", "mkv", "avi", "mov", "flv", "wmv", "m4v", "webm").contains(extension) -> MediaType.VIDEO
                listOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "wma", "opus", "amr").contains(extension) -> MediaType.AUDIO
                listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif").contains(extension) -> MediaType.IMAGE
                else -> MediaType.UNKNOWN
            }
        } finally {
            // Always release the retriever to prevent resource leaks
            retriever?.release()
        }
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        val channel = MethodChannel(flutterPluginBinding.flutterEngine.dartExecutor, methodName)
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "createAlbum" -> createAlbum(call, result)
            "saveAlbum" -> saveAlbum(call, result)
            "saveAlbumWithLocation" -> saveAlbumWithLocation(call, result)
            else -> result.notImplemented()
        }
    }

    private fun createAlbum(call: MethodCall, result: Result) {
        val albumName = call.argument<String>("albumName")
        if (albumName == null) {
            result.error("100", "albumName cannot be null", null)
            return
        }
        thread {
            // For API < 29 we create the album folder manually.
            if (Build.VERSION.SDK_INT < 29) {
                // For images, we use DCIM.
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val albumDir = File(dcimDir, albumName)
                if (!albumDir.exists()) albumDir.mkdirs()
            }
            handler.post { result.success(true) }
        }
    }

    private fun saveAlbum(call: MethodCall, result: Result) {
        // Get the album name and file paths from the call arguments.
        val albumName = call.argument<String>("albumName")
        val filePaths = call.argument<List<String>>("filePaths")

        if (albumName == null) {
            result.error("100", "albumName cannot be null", null)
            return
        }
        if (filePaths == null) {
            result.error("101", "filePaths cannot be null", null)
            return
        }

        thread {
            try {
                // Process each file path.
                for (path in filePaths) {
                    // Detect the actual media type
                    val mediaType = detectMediaType(path)
                    
                    // Skip audio files as they're not supported in photo albums
                    if (mediaType == MediaType.AUDIO) {
                        println("Skipping audio file (not supported in Photos): $path")
                        continue
                    }
                    
                    // Skip unknown file types
                    if (mediaType == MediaType.UNKNOWN) {
                        println("Skipping unknown file type: $path")
                        continue
                    }
                    
                    // Extract the file extension for naming
                    val extension = if (path.contains(".")) path.substringAfterLast(".") else ""
                    // Generate a unique file name.
                    val fileName = if (extension.isNotEmpty()) "${System.currentTimeMillis()}.$extension" else "${System.currentTimeMillis()}"
                    
                    val isVideo = (mediaType == MediaType.VIDEO)

                    if (Build.VERSION.SDK_INT >= 29) {
                        val resolver = context?.contentResolver
                        if (isVideo) {
                            // Save video using MediaStore.Video for Android 10+.
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                                // Use proper MIME type for video, handle m4a -> mp4 conversion
                                val videoExtension = when(extension.lowercase(java.util.Locale.ROOT)) {
                                    "m4a", "m4v" -> "mp4"
                                    "" -> "mp4"
                                    else -> extension
                                }
                                put(MediaStore.Video.Media.MIME_TYPE, "video/$videoExtension")
                                // Use Movies directory for videos.
                                put("relative_path", "Movies/$albumName")
                                put("is_pending", 1)
                            }
                            val uri: Uri? = resolver?.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { outputStream ->
                                    FileInputStream(path).use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                values.clear()
                                values.put("is_pending", 0)
                                resolver?.update(uri, values, null, null)
                            }
                        } else {
                            // Save image using MediaStore.Images for Android 10+.
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/${if (extension.isNotEmpty()) extension else "jpeg"}")
                                put("relative_path", "DCIM/$albumName")
                                put("is_pending", 1)
                            }
                            val uri: Uri? = resolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { outputStream ->
                                    FileInputStream(path).use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                values.clear()
                                values.put("is_pending", 0)
                                resolver?.update(uri, values, null, null)
                            }
                        }
                    } else {
                        // For Android versions below 29, use direct file system access.
                        if (isVideo) {
                            // Save video to Movies directory.
                            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            val albumDir = File(moviesDir, albumName)
                            if (!albumDir.exists()) albumDir.mkdirs()
                            val file = File(albumDir, fileName)
                            FileInputStream(path).use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            context?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                        } else {
                            // Save image to DCIM directory.
                            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val albumDir = File(dcimDir, albumName)
                            if (!albumDir.exists()) albumDir.mkdirs()
                            val file = File(albumDir, fileName)
                            FileInputStream(path).use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            context?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                        }
                    }
                }
                // Once all files are processed, return success.
                handler.post {
                    result.success(true)
                }
            } catch (e: Exception) {
                // Return error if saving fails.
                handler.post {
                    result.error("102", "Error saving album: ${e.message}", null)
                }
            }
        }
    }

    private fun saveAlbumWithLocation(call: MethodCall, result: Result) {
        // Get the album name, file paths, and location from the call arguments.
        val albumName = call.argument<String>("albumName")
        val filePaths = call.argument<List<String>>("filePaths")
        val latitude = call.argument<Double>("latitude")
        val longitude = call.argument<Double>("longitude")

        if (albumName == null) {
            result.error("100", "albumName cannot be null", null)
            return
        }
        if (filePaths == null) {
            result.error("101", "filePaths cannot be null", null)
            return
        }

        thread {
            try {
                // Process each file path.
                for (path in filePaths) {
                    // Detect the actual media type
                    val mediaType = detectMediaType(path)
                    
                    // Skip audio files as they're not supported in photo albums
                    if (mediaType == MediaType.AUDIO) {
                        println("Skipping audio file (not supported in Photos): $path")
                        continue
                    }
                    
                    // Skip unknown file types
                    if (mediaType == MediaType.UNKNOWN) {
                        println("Skipping unknown file type: $path")
                        continue
                    }
                    
                    // Extract the file extension for naming
                    val extension = if (path.contains(".")) path.substringAfterLast(".") else ""
                    // Generate a unique file name.
                    val fileName = if (extension.isNotEmpty()) "${System.currentTimeMillis()}.$extension" else "${System.currentTimeMillis()}"
                    
                    val isVideo = (mediaType == MediaType.VIDEO)

                    if (Build.VERSION.SDK_INT >= 29) {
                        val resolver = context?.contentResolver
                        if (isVideo) {
                            // Save video using MediaStore.Video for Android 10+.
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                                // Use proper MIME type for video, handle m4a -> mp4 conversion
                                val videoExtension = when(extension.lowercase(java.util.Locale.ROOT)) {
                                    "m4a", "m4v" -> "mp4"
                                    "" -> "mp4"
                                    else -> extension
                                }
                                put(MediaStore.Video.Media.MIME_TYPE, "video/$videoExtension")
                                // Use Movies directory for videos.
                                put("relative_path", "Movies/$albumName")
                                put("is_pending", 1)
                                // Add location data if available
                                if (latitude != null && longitude != null) {
                                    put(MediaStore.Video.Media.LATITUDE, latitude)
                                    put(MediaStore.Video.Media.LONGITUDE, longitude)
                                }
                            }
                            val uri: Uri? = resolver?.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { outputStream ->
                                    FileInputStream(path).use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                values.clear()
                                values.put("is_pending", 0)
                                resolver?.update(uri, values, null, null)
                            }
                        } else {
                            // Save image using MediaStore.Images for Android 10+.
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/${if (extension.isNotEmpty()) extension else "jpeg"}")
                                put("relative_path", "DCIM/$albumName")
                                put("is_pending", 1)
                                // Add location data if available
                                if (latitude != null && longitude != null) {
                                    put(MediaStore.Images.Media.LATITUDE, latitude)
                                    put(MediaStore.Images.Media.LONGITUDE, longitude)
                                }
                            }
                            val uri: Uri? = resolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                resolver.openOutputStream(uri)?.use { outputStream ->
                                    FileInputStream(path).use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                                values.clear()
                                values.put("is_pending", 0)
                                resolver?.update(uri, values, null, null)
                            }
                        }
                    } else {
                        // For Android versions below 29, use direct file system access.
                        // Note: Location data cannot be added directly for API < 29
                        if (isVideo) {
                            // Save video to Movies directory.
                            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                            val albumDir = File(moviesDir, albumName)
                            if (!albumDir.exists()) albumDir.mkdirs()
                            val file = File(albumDir, fileName)
                            FileInputStream(path).use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            context?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                        } else {
                            // Save image to DCIM directory.
                            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                            val albumDir = File(dcimDir, albumName)
                            if (!albumDir.exists()) albumDir.mkdirs()
                            val file = File(albumDir, fileName)
                            FileInputStream(path).use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            context?.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
                        }
                    }
                }
                // Once all files are processed, return success.
                handler.post {
                    result.success(true)
                }
            } catch (e: Exception) {
                // Return error if saving fails.
                handler.post {
                    result.error("102", "Error saving album: ${e.message}", null)
                }
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
    }
}