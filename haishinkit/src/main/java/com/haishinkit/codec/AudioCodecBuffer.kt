package com.haishinkit.codec

import androidx.core.util.Pools
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

internal class AudioCodecBuffer {
    var sampleRate: Int = 44100
    var presentationTimestamp: Long = DEFAULT_PRESENTATION_TIMESTAMP
        private set
    private var pool = Pools.SynchronizedPool<ByteBuffer>(CAPACITY * 2)
    private var buffers = LinkedBlockingDeque<ByteBuffer>(CAPACITY)

    fun append(byteBuffer: ByteBuffer) {
        val buffer = pool.acquire() ?: ByteBuffer.allocateDirect(byteBuffer.capacity())
        buffer.rewind()
        buffer.put(byteBuffer)
        // Lock-free callers race with render()/clear(): a size-check followed by pop() can hit an
        // emptied deque and throw, killing the capture coroutine upstream. Use the non-throwing
        // offer/pollFirst pair instead, dropping the oldest frame (returned to the pool) when full.
        var attempts = 0
        while (!buffers.offer(buffer)) {
            buffers.pollFirst()?.let { pool.release(it) }
            if (++attempts > CAPACITY) {
                pool.release(buffer)
                return
            }
        }
    }

    fun render(byteBuffer: ByteBuffer): Int {
        val buffer = buffers.take()
        buffer.rewind()
        val start = byteBuffer.position()
        byteBuffer.put(buffer)
        pool.release(buffer)
        val result = byteBuffer.position() - start
        if (presentationTimestamp == DEFAULT_PRESENTATION_TIMESTAMP) {
            presentationTimestamp = System.nanoTime() / 1000
        } else {
            presentationTimestamp += timestamp(result / 2)
        }
        return result
    }

    fun clear() {
        buffers.clear()
        presentationTimestamp = DEFAULT_PRESENTATION_TIMESTAMP
    }

    // Intentionally returns 0 (upstream behavior): render()'s `result` is the bytes copied from a
    // pooled buffer whose capacity can exceed the valid PCM payload, so deriving a duration from it
    // overruns real time (measured 8.5x on Samsung A14 — receivers then see audio racing ahead of
    // video and discard it). With a frozen input PTS the MediaCodec AAC encoder interpolates output
    // timestamps from the anchor by consumed samples, which measures correct on-device.
    private fun timestamp(sampleCount: Int): Long = ((sampleCount.toFloat() / sampleRate.toFloat())).toLong()

    companion object {
        const val CAPACITY = 4
        const val DEFAULT_PRESENTATION_TIMESTAMP = 0L
    }
}
