/*
 * Copyright (C) 2023 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtsp.rtsp

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.BitrateManager
import com.pedro.common.ConnectChecker
import com.pedro.common.onMainThread
import com.pedro.common.trySend
import com.pedro.rtsp.rtcp.BaseSenderReport
import com.pedro.rtsp.rtp.packets.*
import com.pedro.rtsp.rtp.sockets.BaseRtpSocket
import com.pedro.rtsp.rtp.sockets.RtpSocketTcp
import com.pedro.rtsp.utils.RtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.*

/**
 * Created by pedro on 7/11/18.
 */
class RtspSender(private val connectChecker: ConnectChecker) {

  private var videoPacket: BasePacket? = null
  private var audioPacket: BasePacket? = null
  private var rtpSocket: BaseRtpSocket? = null
  private var baseSenderReport: BaseSenderReport? = null

  private val defaultCacheSize: Int
    get() = 10 * 1024 * 1024 / RtpConstants.MTU
  private var cacheSize = defaultCacheSize
  @Volatile
  private var running = false

  private var job: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO)
  @Volatile
  private var queue: BlockingQueue<RtpFrame> = LinkedBlockingQueue(cacheSize)

  private var audioFramesSent: Long = 0
  private var videoFramesSent: Long = 0
  var droppedAudioFrames: Long = 0
    private set
  var droppedVideoFrames: Long = 0
    private set
  private val bitrateManager: BitrateManager = BitrateManager(connectChecker)
  private var isEnableLogs = true

  companion object {
    private const val TAG = "RtspSender"
  }

  @Throws(IOException::class)
  fun setSocketsInfo(protocol: Protocol, videoSourcePorts: IntArray, audioSourcePorts: IntArray) {
    rtpSocket = BaseRtpSocket.getInstance(protocol, videoSourcePorts[0], audioSourcePorts[0])
    baseSenderReport = BaseSenderReport.getInstance(protocol, videoSourcePorts[1], audioSourcePorts[1])
  }

  fun setVideoInfo(sps: ByteArray, pps: ByteArray?, vps: ByteArray?) {
    videoPacket = when {
      vps == null && pps == null -> Av1Packet()
      vps == null && pps != null -> H264Packet(sps, pps)
      vps != null && pps != null -> H265Packet()
      else -> H264Packet(sps, byteArrayOf())
    }
  }

  fun setAudioInfo(sampleRate: Int, audioCodec: AudioCodec) {
    audioPacket = if (audioCodec == AudioCodec.G711) G711Packet(sampleRate) else AacPacket(sampleRate)
  }

  @Throws(IOException::class)
  fun setDataStream(outputStream: OutputStream, host: String) {
    rtpSocket?.setDataStream(outputStream, host)
    baseSenderReport?.setDataStream(outputStream, host)
  }

  fun setVideoPorts(rtpPort: Int, rtcpPort: Int) {
    videoPacket?.setPorts(rtpPort, rtcpPort)
  }

  fun setAudioPorts(rtpPort: Int, rtcpPort: Int) {
    audioPacket?.setPorts(rtpPort, rtcpPort)
  }

  fun sendVideoFrame(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (running) {
      videoPacket?.createAndSendPacket(h264Buffer, info) { rtpFrame ->
        val result = queue.trySend(rtpFrame)
        if (!result) {
          Log.i(TAG, "Video frame discarded")
          droppedVideoFrames++
        }
      }
    }
  }

  fun sendAudioFrame(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (running) {
      audioPacket?.createAndSendPacket(aacBuffer, info) { rtpFrame ->
        val result = queue.trySend(rtpFrame)
        if (!result) {
          Log.i(TAG, "Audio frame discarded")
          droppedAudioFrames++
        }
      }
    }
  }

  fun start() {
    queue.clear()
    running = true
    job = scope.launch {
      val ssrcVideo = Random().nextInt().toLong()
      val ssrcAudio = Random().nextInt().toLong()
      baseSenderReport?.setSSRC(ssrcVideo, ssrcAudio)
      videoPacket?.setSSRC(ssrcVideo)
      audioPacket?.setSSRC(ssrcAudio)
      val isTcp = rtpSocket is RtpSocketTcp
      var bytesSend = 0L
      val bitrateTask = async {
        while (scope.isActive && running) {
          //bytes to bits
          bitrateManager.calculateBitrate(bytesSend * 8)
          bytesSend = 0
          delay(timeMillis = 1000)
        }
      }
      while (scope.isActive && running) {
        val error = runCatching {
          val rtpFrame = runInterruptible {
            queue.poll(1, TimeUnit.SECONDS)
          }
          if (rtpFrame != null) {
            rtpSocket?.sendFrame(rtpFrame, isEnableLogs)
            //4 is tcp header length
            val packetSize = if (isTcp) rtpFrame.length + 4 else rtpFrame.length
            bytesSend += packetSize
            if (rtpFrame.isVideoFrame()) {
              videoFramesSent++
            } else {
              audioFramesSent++
            }
            if (baseSenderReport?.update(rtpFrame, isEnableLogs) == true) {
              //4 is tcp header length
              val reportSize = if (isTcp) baseSenderReport?.PACKET_LENGTH ?: (0 + 4) else baseSenderReport?.PACKET_LENGTH ?: 0
              bytesSend += reportSize
            }
          }
        }.exceptionOrNull()
        if (error != null) {
          onMainThread {
            connectChecker.onConnectionFailed("Error send packet, ${error.message}")
          }
          Log.e(TAG, "send error: ", error)
          return@launch
        }
      }
    }
  }

  suspend fun stop() {
    running = false
    baseSenderReport?.reset()
    baseSenderReport?.close()
    rtpSocket?.close()
    audioPacket?.reset()
    videoPacket?.reset()
    resetSentAudioFrames()
    resetSentVideoFrames()
    resetDroppedAudioFrames()
    resetDroppedVideoFrames()
    job?.cancelAndJoin()
    job = null
    queue.clear()
  }

  @Throws(IllegalArgumentException::class)
  fun hasCongestion(percentUsed: Float = 20f): Boolean {
    if (percentUsed < 0 || percentUsed > 100) throw IllegalArgumentException("the value must be in range 0 to 100")
    val size = queue.size.toFloat()
    val remaining = queue.remainingCapacity().toFloat()
    val capacity = size + remaining
    return size >= capacity * (percentUsed / 100f)
  }

  fun resizeCache(newSize: Int) {
    if (newSize < queue.size - queue.remainingCapacity()) {
      throw RuntimeException("Can't fit current cache inside new cache size")
    }
    val tempQueue: BlockingQueue<RtpFrame> = LinkedBlockingQueue(newSize)
    queue.drainTo(tempQueue)
    queue = tempQueue
  }

  fun getCacheSize(): Int {
    return cacheSize
  }

  fun getItemsInCache(): Int = queue.size

  fun clearCache() {
    queue.clear()
  }

  fun getSentAudioFrames(): Long {
    return audioFramesSent
  }

  fun getSentVideoFrames(): Long {
    return videoFramesSent
  }

  fun resetSentAudioFrames() {
    audioFramesSent = 0
  }

  fun resetSentVideoFrames() {
    videoFramesSent = 0
  }

  fun resetDroppedAudioFrames() {
    droppedAudioFrames = 0
  }

  fun resetDroppedVideoFrames() {
    droppedVideoFrames = 0
  }

  fun setLogs(enable: Boolean) {
    isEnableLogs = enable
  }
}