package com.rhyme.r_album

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        val channel = MethodChannel(flutterPluginBinding.flutterEngine.dartExecutor, methodName)
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "createAlbum" -> createAlbum(call, result)
            "saveAlbum" -> saveAlbum(call, result)
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
                // List of common video extensions.
                val videoExtensions = listOf("mp4", "3gp", "mkv", "avi", "mov", "flv", "wmv")

                // Process each file path.
                for (path in filePaths) {
                    // Extract the file extension.
                    val extension = if (path.contains(".")) path.substringAfterLast(".") else ""
                    // Determine if this is a video based on the extension.
                    val isVideo = videoExtensions.contains(extension.lowercase())
                    // Generate a unique file name.
                    val fileName = if (extension.isNotEmpty()) "${System.currentTimeMillis()}.$extension" else "${System.currentTimeMillis()}"

                    if (Build.VERSION.SDK_INT >= 29) {
                        val resolver = context?.contentResolver
                        if (isVideo) {
                            // Save video using MediaStore.Video for Android 10+.
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Video.Media.MIME_TYPE, "video/${if (extension.isNotEmpty()) extension else "mp4"}")
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

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        context = null
    }
}