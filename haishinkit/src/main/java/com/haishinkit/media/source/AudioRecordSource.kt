package com.haishinkit.media.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import java.nio.ByteBuffer

/**
 * An audio source that captures a microphone by the AudioRecord api.
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudioRecordSource(
    private val context: Context,
) : AudioSource {
    override var isMuted = false
    var channel = DEFAULT_CHANNEL
    var audioSource = DEFAULT_AUDIO_SOURCE
    var sampleRate = DEFAULT_SAMPLE_RATE
    var minBufferSize = -1
        get() {
            if (field == -1) {
                // 8x the OS minimum (~40ms): with the bare minimum any scheduling stall over the
                // buffer depth makes the HAL drop samples silently — measured as the audio track
                // advancing at ~0.92x wall clock (5s/min of drift against video).
                field = AudioRecord.getMinBufferSize(sampleRate, channel, encoding) * 8
            }
            return field
        }
    var audioRecord: AudioRecord? = null
        get() {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            if (field == null) {
                field = createAudioRecord(audioSource, sampleRate, channel, encoding, minBufferSize)
            }
            return field
        }
        private set

    private var encoding = DEFAULT_ENCODING
    private var sampleCount = DEFAULT_SAMPLE_COUNT
    private var statReads = 0L
    private var statBytes = 0L
    private var statEmpty = 0L
    private var statSinceNanos = 0L
    private var noSignalBuffer = ByteBuffer.allocateDirect(0)
    private var byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(sampleCount * 2)

    override suspend fun open(mixer: MediaMixer): Result<Unit> {
        try {
            audioRecord?.startRecording()
        } catch (e: IllegalStateException) {
            return Result.failure(e)
        }
        return Result.success(Unit)
    }

    override suspend fun close(): Result<Unit> {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: java.lang.IllegalStateException) {
            Log.w(TAG, e)
            return Result.failure(e)
        } finally {
            // A released AudioRecord must not be reused: startRecording() on it throws and read()
            // then returns an error code immediately, which spun the capture loop at CPU speed
            // shipping stale PCM (audio timestamps raced ~9x ahead of video, receivers dropped the
            // track). Null the field so the next open() builds a fresh recorder.
            audioRecord = null
        }
        return Result.success(Unit)
    }

    override fun read(track: Int): MediaBuffer {
        byteBuffer.clear()
        val result = audioRecord?.read(byteBuffer, sampleCount * 2) ?: -1
        if (result <= 0) {
            statEmpty += 1
            // No data (recorder released, not started, or mid-transition): pace the loop instead of
            // busy-spinning, and ship an empty payload so no stale PCM reaches the encoder.
            Thread.sleep(10)
            byteBuffer.position(0)
            byteBuffer.limit(0)
            return MediaBuffer(
                type = MediaType.AUDIO,
                index = track,
                payload = byteBuffer,
                timestamp = 0,
                sync = true,
            )
        }
        statReads += 1
        statBytes += result
        if (statSinceNanos == 0L) statSinceNanos = System.nanoTime()
        if (statReads % 512 == 0L) {
            val secs = (System.nanoTime() - statSinceNanos) / 1e9
            val hz = statBytes / 2 / secs
            Log.i(TAG, "mic stats: reads=$statReads empty=$statEmpty bytes=$statBytes wall=${"%.1f".format(secs)}s effRate=${hz.toInt()}Hz (want=$sampleRate)")
            statBytes = 0; statEmpty = 0; statSinceNanos = System.nanoTime()
        }
        if (isMuted) {
            for (i in 0 until result) {
                byteBuffer.put(i, 0)
            }
        }
        return MediaBuffer(
            type = MediaType.AUDIO,
            index = track,
            payload = byteBuffer,
            timestamp = 0,
            sync = true,
        )
    }

    companion object {
        const val DEFAULT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val DEFAULT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.CAMCORDER
        const val DEFAULT_SAMPLE_COUNT = 1024

        @SuppressLint("MissingPermission")
        private fun createAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channel: Int,
            encoding: Int,
            minBufferSize: Int,
        ): AudioRecord {
            if (Build.VERSION_CODES.M <= Build.VERSION.SDK_INT) {
                return try {
                    AudioRecord
                        .Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat
                                .Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channel)
                                .build(),
                        ).setBufferSizeInBytes(minBufferSize)
                        .build()
                } catch (_: Exception) {
                    AudioRecord(
                        audioSource,
                        sampleRate,
                        channel,
                        encoding,
                        minBufferSize,
                    )
                }
            } else {
                return AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    encoding,
                    minBufferSize,
                )
            }
        }

        private val TAG = AudioRecordSource::class.java.simpleName
    }
}
