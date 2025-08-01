package com.techind.flutter_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.URLUtil
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection

private const val EVENTS_CHANNEL_MEDIA = "flutter_sharing_intent/events-sharing"

/**
 **  Author - Bhagat Singh
 **  Contact - https://www.linkedin.com/in/bhagat-singh-79496a14b/
 **  Created On - 25/11/2022
 **  Purpose -  Created [FlutterSharingIntentPlugin] class to manage sharing intent
 */

class FlutterSharingIntentPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
  EventChannel.StreamHandler,
  PluginRegistry.NewIntentListener {
  private var TAG: String = javaClass.name

  /** To store initial & latest value when app is opened from background **/
  private var initialSharing: JSONArray? = null
  private var latestSharing: JSONArray? = null

  /// The MethodChannel that will the communication between Flutter and native Android
  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel

  private var eventSinkSharing: EventChannel.EventSink? = null

  private var binding: ActivityPluginBinding? = null
  private lateinit var applicationContext: Context

  /// To sel channel & event stream
  private fun setupCallbackChannels(binaryMessenger: BinaryMessenger) {
    channel = MethodChannel(binaryMessenger, "flutter_sharing_intent")
    channel.setMethodCallHandler(this)

    eventChannel = EventChannel(binaryMessenger, EVENTS_CHANNEL_MEDIA)
    eventChannel.setStreamHandler(this)

  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    applicationContext = flutterPluginBinding.applicationContext
    setupCallbackChannels(flutterPluginBinding.binaryMessenger)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getInitialSharing" -> {
        result.success(initialSharing?.toString())
        /// Clear cache data to send only once
        initialSharing = null
        latestSharing = null
      }

      "reset" -> {
        initialSharing = null
        latestSharing = null
        result.success(null)
      }

      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    initialSharing = null
    latestSharing = null
    eventChannel.setStreamHandler(null)
  }

  private fun handleIntent(intent: Intent, initial: Boolean) {
    val intentFlags = intent.getFlags()
    if ((intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
      Log.w(TAG, "handleIntent ==>> ${intent.action}, ${intent.type}")
    when {
        (intent.type != null && intent.type?.startsWith("text") != true)
                && (intent.action == Intent.ACTION_SEND
                || intent.action == Intent.ACTION_SEND_MULTIPLE) -> { // Sharing images or videos


          val value = getSharingUris(intent)
          if (initial) initialSharing = value
          latestSharing = value
          Log.w(TAG, "handleIntentImage ==>> $value")
          eventSinkSharing?.success(latestSharing?.toString())
        }

        (intent.type == null || intent.type?.startsWith("text") == true)
                && (intent.action == Intent.ACTION_SEND
                || intent.action == Intent.ACTION_SEND_MULTIPLE) -> { // Sharing text

          val value = getSharingText(intent) ?: getSharingUris(intent)
          if (initial) initialSharing = value
          latestSharing = value
          Log.w(TAG, "handleIntentText ==>> $value")
          eventSinkSharing?.success(latestSharing?.toString())

        }

        intent.action == Intent.ACTION_VIEW -> { // Opening URL
          val value = JSONArray().put(
            JSONObject()
              .put("value", intent.dataString)
              .put("type", MediaType.URL.ordinal)
          )
          if (initial) initialSharing = value
          latestSharing = value
          Log.w(TAG, "handleIntentURL ==>> $value")
          eventSinkSharing?.success(latestSharing?.toString())
        }
      }
    }
  }

  private fun getSharingUris(intent: Intent?): JSONArray? {
    if (intent == null) return null

    return when (intent.action) {
      Intent.ACTION_SEND -> {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val path = uri?.let { MyFileDirectory.getAbsolutePath(applicationContext, it) }

        val size = getSize(uri)
        val name = getName(uri)

        if (path != null) {
          val type = getMediaType(path)
          val thumbnail = getThumbnail(path, type)
          val duration = getDuration(path, type)

          JSONArray().put(
            JSONObject()
              .put("name", name)
              .put("value", path)
              .put("type", type.ordinal)
              .put("thumbnail", thumbnail)
              .put("duration", duration)
              .put("size", size)
          )
        } else null
      }

      Intent.ACTION_SEND_MULTIPLE -> {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        println("URIS: ${uris?.isEmpty()}")
        val value = uris?.mapNotNull { uri ->
          val path = MyFileDirectory.getAbsolutePath(applicationContext, uri)
          val size = getSize(uri)
          val name = getName(uri)
          if (path != null) {
            val type = getMediaType(path)
            val thumbnail = getThumbnail(path, type)
            val duration = getDuration(path, type)
            return@mapNotNull JSONObject()
              .put("name", name)
              .put("value", path)
              .put("type", type.ordinal)
              .put("thumbnail", thumbnail)
              .put("duration", duration)
              .put("size", size)
          } else null
        }?.toList()
        if (value != null) JSONArray(value) else null
      }

      else -> null
    }
  }

  private fun getSharingText(intent: Intent?): JSONArray? {
    if (intent == null) return null

    return when (intent.action) {
      Intent.ACTION_SEND -> {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null) {
          val type = getTypeForTextAndUrl(text)
          JSONArray().put(
            JSONObject()
              .put("value", text)
              .put("type", type)
          )
        } else null
      }

      Intent.ACTION_SEND_MULTIPLE -> {
        val textList = intent.getStringArrayListExtra(Intent.EXTRA_TEXT)

        val value = textList?.mapNotNull { text ->
          val path = text
            ?: return@mapNotNull null
          val type = getTypeForTextAndUrl(path)

          return@mapNotNull JSONObject()
            .put("value", path)
            .put("type", type)
        }?.toList()
        if (value != null) JSONArray(value) else null
      }

      else -> null
    }
  }

  // To get type for text and url only
  // It will return MediaType.URL.ordinal if text is valid url other will return MediaType.TEXT.ordinal
  fun getTypeForTextAndUrl(value: String?): Int {
    return if (value == null || !URLUtil.isValidUrl(value)) MediaType.TEXT.ordinal else MediaType.URL.ordinal;
  }

  private fun getMediaType(path: String?): MediaType {
    val mimeType = URLConnection.guessContentTypeFromName(path)
    return when {
      mimeType?.startsWith("image") == true -> MediaType.IMAGE
      mimeType?.startsWith("video") == true -> MediaType.VIDEO
      mimeType?.startsWith("text") == true -> MediaType.TEXT
      mimeType?.startsWith("url") == true -> MediaType.URL
      else -> MediaType.FILE
    }
  }

  private fun getThumbnail(path: String, type: MediaType): String? {
    if (type != MediaType.VIDEO) return null // get video thumbnail only

    val videoFile = File(path)
    val targetFile = File(applicationContext.cacheDir, "${videoFile.name}.png")
    val bitmap =
      ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
        ?: return null
    FileOutputStream(targetFile).use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    bitmap.recycle()
    return targetFile.path
  }

  private fun getDuration(path: String, type: MediaType): Long? {
    if (type != MediaType.VIDEO) return null // get duration for video only
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(path)
    val duration =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    retriever.release()
    return duration
  }

  private fun getName(uri: Uri?): String? {
    var fileName: String? = null
    val cursor = applicationContext.contentResolver
      .query(uri!!, null, null, null, null, null)
    try {
      if (cursor != null && cursor.moveToFirst()) {
        // get file name
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (!cursor.isNull(nameIndex)) {
          fileName = cursor.getString(nameIndex)
        }
      }
    } finally {
      cursor!!.close()
    }
    return fileName
  }

  private fun getSize(uri: Uri?): String? {
    var fileSize: String? = null
    val cursor = applicationContext.contentResolver
      .query(uri!!, null, null, null, null, null)
    try {
      if (cursor != null && cursor.moveToFirst()) {
        // get file size
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (!cursor.isNull(sizeIndex)) {
          fileSize = cursor.getString(sizeIndex)
        }
      }
    } finally {
      cursor!!.close()
    }
    return fileSize
  }

  enum class MediaType {
    TEXT, URL, IMAGE, VIDEO, FILE;
  }

  override fun onNewIntent(intent: Intent): Boolean {
    handleIntent(intent, false)
    return false
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.binding = binding
    binding.addOnNewIntentListener(this)
    handleIntent(binding.activity.intent, true)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    binding?.removeOnNewIntentListener(this)
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    this.binding = binding
    binding.addOnNewIntentListener(this)
  }

  override fun onDetachedFromActivity() {
    binding?.removeOnNewIntentListener(this)
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    when (arguments) {
      "sharing" -> eventSinkSharing = events
    }
  }

  override fun onCancel(arguments: Any?) {
    when (arguments) {
      "sharing" -> eventSinkSharing = null
    }
  }


}
