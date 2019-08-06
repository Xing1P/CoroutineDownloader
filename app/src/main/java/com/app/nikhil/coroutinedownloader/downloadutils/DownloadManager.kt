package com.app.nikhil.coroutinedownloader.downloadutils

import com.app.nikhil.coroutinedownloader.entity.DownloadInfo
import com.app.nikhil.coroutinedownloader.utils.FileExistsException
import com.app.nikhil.coroutinedownloader.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.BufferedSource
import okio.appendingSink
import okio.buffer
import okio.sink
import timber.log.Timber
import java.math.RoundingMode.CEILING
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@ExperimentalCoroutinesApi
class DownloadManager @Inject constructor(
  private val okHttpClient: OkHttpClient,
  private val fileUtils: FileUtils,
  override val coroutineContext: CoroutineContext = Dispatchers.IO
) : CoroutineScope, Downloader {

  companion object {
    private const val BYTES_MULTIPLIER = 0.000001
    private const val DECIMAL_PERCENT_FORMAT = "#.##"
  }

  private val percentageFormat =
    DecimalFormat(DECIMAL_PERCENT_FORMAT).apply { roundingMode = CEILING }

  private val downloadMap: MutableMap<String, Pair<Job, Channel<DownloadInfo>>> = mutableMapOf()

  // Pause the Queue when the service is destroyed.
  override fun pauseQueue() {}

  // Resume the Queue when the service is started.
  override fun resumeQueue() {}

  // Dispose the resources occupied by the downloader.
  override fun dispose() {}

  override fun download(url: String) {
    val request = Request.Builder()
        .url(url)
        .build()

    val channel = Channel<DownloadInfo>()
    val job = launch {
      try {
        // Create a connection and get the details about the file.
        val response = okHttpClient.newCall(request)
            .execute()
        // Get the file object for the file to be downloaded.
        val file = fileUtils.getFile(url)

        // If the body of response is not empty
        response.body?.let { body ->
          if (file.exists() && file.length() == body.contentLength()) {
            throw FileExistsException()
          }
          // Create a buffered output stream (BufferedSink) for the file.
          val fileBufferedSink: BufferedSink = when {
            file.length() != 0L -> file.appendingSink()
                .buffer()
            else -> file.sink()
                .buffer()
          }
          // Get the buffered input stream (BufferedStream) for the file.
          val networkBufferedSource = body.source()
          bufferedRead(
              networkBufferedSource, fileBufferedSink, 40.toLong(),
              body.contentLength(), channel, file.length(), url
          )
        }
      } catch (e: Exception) {
        throw e
      }
    }
    downloadMap[url] = Pair(job, channel)
  }

  override fun pause(url: String) {
    downloadMap[url]?.let { pair ->
      pair.first.cancel()
      pair.second.close()
    }
  }

  override fun getChannel(url: String): Channel<DownloadInfo>? {
    return downloadMap[url]?.second
  }

  /*
  * Read from a BufferedSource and write it in BufferedSink
  */
  private suspend fun bufferedRead(
    source: BufferedSource,
    sink: BufferedSink,
    bufferSize: Long,
    totalBytes: Long,
    channel: Channel<DownloadInfo>,
    seek: Long = 0L,
    url: String
  ) {
    var bytesRead = seek
    try {
      source.skip(seek)
      var noOfBytes = source.read(sink.buffer, bufferSize)
      while (noOfBytes != -1L) {
        bytesRead += noOfBytes
        noOfBytes = source.read(sink.buffer, bufferSize)
        publishUpdates(channel, bytesRead, totalBytes, url)
      }
      if (bytesRead != totalBytes) {
        bytesRead = source.read(sink.buffer, totalBytes - bytesRead)
        publishUpdates(channel, bytesRead, totalBytes, url)
      }
    } catch (e: Exception) {
      Timber.e(e)
      throw e
    } finally {
      source.close()
      sink.close()
    }
  }

  private suspend fun publishUpdates(
    channel: Channel<DownloadInfo>,
    bytesRead: Long,
    totalBytes: Long,
    url: String
  ) {
    try {
      if (!channel.isClosedForSend) {
        channel.send(
            DownloadInfo(
                url = url,
                percentage = getPercentage(bytesRead, totalBytes),
                bytesDownloaded = convertBytesToMB(bytesRead),
                totalBytes = convertBytesToMB(totalBytes)
            )
        )
      }
    } catch (e: Exception) {
      Timber.e(e)
      throw e
    }
  }

  private fun getPercentage(
    bytesRead: Long,
    totalBytes: Long
  ): String = percentageFormat.format((bytesRead.toDouble() / totalBytes.toDouble()) * 100)

  private fun convertBytesToMB(bytes: Long): String =
    percentageFormat.format(bytes * BYTES_MULTIPLIER)

}