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

package com.pedro.library.base

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.encoder.EncoderErrorCallback
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAacData
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.library.base.recording.BaseRecordController
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.AndroidMuxerRecordController
import com.pedro.library.util.sources.audio.AudioSource
import com.pedro.library.util.sources.video.NoVideoSource
import com.pedro.library.util.sources.video.VideoSource
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.library.view.GlStreamInterface
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLSurface

/**
 * Created by pedro on 21/2/22.
 *
 * Allow:
 * - video source camera1, camera2 or screen.
 * - audio source microphone or internal.
 * - Rotation on realtime.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
abstract class StreamBase(
  context: Context,
  vSource: VideoSource,
  aSource: AudioSource
) {

  private val getMicrophoneData = object: GetMicrophoneData {
    override fun inputPCMData(frame: Frame) {
      audioEncoder.inputPCMData(frame)
    }
  }
  //video and audio encoders
  private val videoEncoder by lazy { VideoEncoder(getVideoData) }
  private val audioEncoder by lazy { AudioEncoder(getAacData) }
  //video render
  private val glInterface = GlStreamInterface(context)
  //video/audio record
  private var recordController: BaseRecordController = AndroidMuxerRecordController()
  var isStreaming = false
    private set
  var isOnPreview = false
    private set
  val isRecording: Boolean
    get() = recordController.isRunning
  var videoSource: VideoSource = vSource
    private set
  var audioSource: AudioSource = aSource
    private set

  /**
   * Necessary only one time before start preview, stream or record.
   * If you want change values stop preview, stream and record is necessary.
   *
   * @param profile codec value from MediaCodecInfo.CodecProfileLevel class
   * @param level codec value from MediaCodecInfo.CodecProfileLevel class
   *
   * @return True if success, False if failed
   */
  @JvmOverloads
  fun prepareVideo(width: Int, height: Int, bitrate: Int, fps: Int = 30, iFrameInterval: Int = 2,
    rotation: Int = 0, profile: Int = -1, level: Int = -1): Boolean {
    if (isStreaming || isRecording || isOnPreview) {
      throw IllegalStateException("Stream, record and preview must be stopped before prepareVideo")
    }
    val videoResult = videoSource.create(width, height, fps)
    if (videoResult) {
      if (rotation == 90 || rotation == 270) glInterface.setEncoderSize(height, width)
      else glInterface.setEncoderSize(width, height) //0, 180
      return videoEncoder.prepareVideoEncoder(width, height, fps, bitrate, rotation,
        iFrameInterval, FormatVideoEncoder.SURFACE, profile, level)
    }
    return false
  }

  /**
   * Necessary only one time before start stream or record.
   * If you want change values stop stream and record is necessary.
   *
   * @return True if success, False if failed
   */
  @JvmOverloads
  fun prepareAudio(sampleRate: Int, isStereo: Boolean, bitrate: Int, echoCanceler: Boolean = false,
    noiseSuppressor: Boolean = false): Boolean {
    if (isStreaming || isRecording) {
      throw IllegalStateException("Stream and record must be stopped before prepareAudio")
    }
    val audioResult = audioSource.create(sampleRate, isStereo, echoCanceler, noiseSuppressor)
    if (audioResult) {
      audioInfo(sampleRate, isStereo)
      return audioEncoder.prepareAudioEncoder(bitrate, sampleRate, isStereo, audioSource.getMaxInputSize())
    }
    return false
  }

  /**
   * Start stream.
   *
   * Must be called after prepareVideo and prepareAudio
   */
  fun startStream(endPoint: String) {
    if (isStreaming) throw IllegalStateException("Stream already started, stopStream before startStream again")
    isStreaming = true
    rtpStartStream(endPoint)
    if (!isRecording) startSources()
    else requestKeyframe()
  }

  fun requestKeyframe() {
    if (videoEncoder.isRunning) {
      videoEncoder.requestKeyframe()
    }
  }

  /**
   * Set video bitrate in bits per second while streaming.
   *
   * @param bitrate in bits per second.
   */
  fun setVideoBitrateOnFly(bitrate: Int) {
    videoEncoder.setVideoBitrateOnFly(bitrate)
  }

  /**
   * @param codecTypeVideo force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   * @param codecTypeAudio force type codec used. FIRST_COMPATIBLE_FOUND, SOFTWARE, HARDWARE
   */
  fun forceCodecType(codecTypeVideo: CodecUtil.CodecType, codecTypeAudio: CodecUtil.CodecType) {
    videoEncoder.forceCodecType(codecTypeVideo)
    audioEncoder.forceCodecType(codecTypeAudio)
  }

  /**
   * Stop stream.
   *
   * @return True if encoders prepared successfully with previous parameters. False other way
   * If return is false you will need call prepareVideo and prepareAudio manually again before startStream or StartRecord
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  fun stopStream(): Boolean {
    isStreaming = false
    rtpStopStream()
    if (!isRecording) {
      stopSources()
      return prepareEncoders()
    }
    return true
  }

  /**
   * Start record.
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  fun startRecord(path: String, listener: RecordController.Listener) {
    if (isRecording) throw IllegalStateException("Record already started, stopRecord before startRecord again")
    recordController.startRecord(path, listener)
    if (!isStreaming) startSources()
    else videoEncoder.requestKeyframe()
  }

  /**
   * @return True if encoders prepared successfully with previous parameters. False other way
   * If return is false you will need call prepareVideo and prepareAudio manually again before startStream or StartRecord
   *
   * Must be called after prepareVideo and prepareAudio.
   */
  fun stopRecord(): Boolean {
    recordController.stopRecord()
    if (!isStreaming) {
      stopSources()
      return prepareEncoders()
    }
    return true
  }

  /**
   * Start preview in the selected TextureView.
   * Must be called after prepareVideo.
   */
  fun startPreview(textureView: TextureView) {
    startPreview(Surface(textureView.surfaceTexture), textureView.width, textureView.height)
  }

  fun startPreview(textureView: TextureView,mEGLSurface: EGLSurface) {
    startPreview(Surface(textureView.surfaceTexture), textureView.width, textureView.height, mEGLSurface)
  }

  /**
   * Start preview in the selected SurfaceView.
   * Must be called after prepareVideo.
   */
  fun startPreview(surfaceView: SurfaceView) {
    startPreview(surfaceView.holder.surface, surfaceView.width, surfaceView.height)
  }

  /**
   * Start preview in the selected SurfaceTexture.
   * Must be called after prepareVideo.
   */
  fun startPreview(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
    startPreview(Surface(surfaceTexture), width, height)
  }

  /**
   * Start preview in the selected Surface.
   * Must be called after prepareVideo.
   */
  fun startPreview(surface: Surface, width: Int, height: Int, mEGLSurface: EGLSurface ) {
    if (!surface.isValid) throw IllegalArgumentException("Make sure the Surface is valid")
    if (isOnPreview) throw IllegalStateException("Preview already started, stopPreview before startPreview again")
    isOnPreview = true
    if (!glInterface.running) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
    glInterface.attachPreview(surface, mEGLSurface)
    glInterface.setPreviewResolution(width, height)
  }

  /**
   * Stop preview.
   * Must be called after prepareVideo.
   */
  fun stopPreview() {
    isOnPreview = false
    if (!isStreaming && !isRecording) videoSource.stop()
    glInterface.deAttachPreview()
    if (!isStreaming && !isRecording) glInterface.stop()
  }

  /**
   * Change video source to Camera1 or Camera2.
   * Must be called after prepareVideo.
   */
  fun changeVideoSource(source: VideoSource) {
    val wasRunning = videoSource.isRunning()
    val wasCreated = videoSource.created
    videoSource.stop()
    videoSource.release()
    videoSource.surfaceTexture?.let {
      if (wasCreated) source.create(videoSource.width, videoSource.height, videoSource.fps)
      if (wasRunning) source.start(it)
      videoSource = source
    }
  }

  /**
   * Change audio source.
   * Must be called after prepareAudio.
   */
  fun changeAudioSource(source: AudioSource) {
    val wasRunning = audioSource.isRunning()
    val wasCreated = audioSource.created
    audioSource.stop()
    audioSource.release()
    if (wasCreated) source.create(audioSource.sampleRate, audioSource.isStereo, audioSource.echoCanceler, audioSource.noiseSuppressor)
    if (wasRunning) source.start(getMicrophoneData)
    audioSource = source
    videoSource.surfaceTexture?.let {

    }
  }

  /**
   * Set a callback to know errors related with Video/Audio encoders
   * @param encoderErrorCallback callback to use, null to remove
   */
  fun setEncoderErrorCallback(encoderErrorCallback: EncoderErrorCallback?) {
    videoEncoder.setEncoderErrorCallback(encoderErrorCallback)
    audioEncoder.setEncoderErrorCallback(encoderErrorCallback)
  }

  /**
   * Change stream orientation depend of activity orientation.
   * This method affect ro preview and stream.
   * Must be called after prepareVideo.
   */
  fun setOrientation(orientation: Int) {
    glInterface.setCameraOrientation(orientation)
  }

  /**
   * Get glInterface used to render video.
   * This is useful to send filters to stream.
   * Must be called after prepareVideo.
   */
  fun getGlInterface(): GlStreamInterface = glInterface

  fun setRecordController(recordController: BaseRecordController) {
    if (!isRecording) this.recordController = recordController
  }

  /**
   * return surface texture that can be used to render and encode custom data. Return null if video not prepared.
   * start and stop rendering must be managed by the user.
   */
  fun getSurfaceTexture(): SurfaceTexture {
    if (videoSource !is NoVideoSource) {
      throw IllegalStateException("getSurfaceTexture only available with VideoManager.Source.DISABLED")
    }
    return glInterface.surfaceTexture
  }

  protected fun getVideoResolution() = Size(videoEncoder.width, videoEncoder.height)

  protected fun getVideoFps() = videoEncoder.fps

  private fun startSources() {
    if (!glInterface.running) glInterface.start()
    if (!videoSource.isRunning()) {
      videoSource.start(glInterface.surfaceTexture)
    }
    audioSource.start(getMicrophoneData)
    videoEncoder.start()
    audioEncoder.start()
    glInterface.addMediaCodecSurface(videoEncoder.inputSurface)
  }

  private fun stopSources() {
    if (!isOnPreview) videoSource.stop()
    audioSource.stop()
    videoEncoder.stop()
    audioEncoder.stop()
    glInterface.removeMediaCodecSurface()
    if (!isOnPreview) glInterface.stop()
    if (!isRecording) recordController.resetFormats()
  }

  fun release() {
    if (isStreaming) stopStream()
    if (isRecording) stopRecord()
    if (isOnPreview) stopPreview()
    stopSources()
    videoSource.release()
  }

  private fun prepareEncoders(): Boolean {
    return videoEncoder.prepareVideoEncoder() && audioEncoder.prepareAudioEncoder()
  }

  private val getAacData: GetAacData = object : GetAacData {
    override fun getAacData(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      getAacDataRtp(aacBuffer, info)
      recordController.recordAudio(aacBuffer, info)
    }

    override fun onAudioFormat(mediaFormat: MediaFormat) {
      recordController.setAudioFormat(mediaFormat)
    }
  }

  private val getVideoData: GetVideoData = object : GetVideoData {
    override fun onSpsPpsVps(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
      onSpsPpsVpsRtp(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
    }

    override fun getVideoData(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
      getH264DataRtp(h264Buffer, info)
      recordController.recordVideo(h264Buffer, info)
    }

    override fun onVideoFormat(mediaFormat: MediaFormat) {
      recordController.setVideoFormat(mediaFormat)
    }
  }

  protected abstract fun audioInfo(sampleRate: Int, isStereo: Boolean)
  protected abstract fun rtpStartStream(endPoint: String)
  protected abstract fun rtpStopStream()
  protected abstract fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
  protected abstract fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo)
  protected abstract fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo)

  abstract fun getStreamClient(): StreamBaseClient

  fun setVideoCodec(codec: VideoCodec) {
    setVideoCodecImp(codec)
    recordController.setVideoCodec(codec)
    val type = when (codec) {
      VideoCodec.H264 -> CodecUtil.H264_MIME
      VideoCodec.H265 -> CodecUtil.H265_MIME
      VideoCodec.AV1 -> CodecUtil.AV1_MIME
    }
    videoEncoder.type = type
  }

  fun setAudioCodec(codec: AudioCodec) {
    setAudioCodecImp(codec)
    recordController.setAudioCodec(codec)
    audioEncoder.type = if (codec == AudioCodec.G711) CodecUtil.G711_MIME else CodecUtil.AAC_MIME
  }

  protected abstract fun setVideoCodecImp(codec: VideoCodec)
  protected abstract fun setAudioCodecImp(codec: AudioCodec)
}