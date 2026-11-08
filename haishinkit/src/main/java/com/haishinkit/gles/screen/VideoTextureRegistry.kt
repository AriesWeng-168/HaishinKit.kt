package com.haishinkit.gles.screen

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.media.source.VideoSource

internal class VideoTextureRegistry : SurfaceTexture.OnFrameAvailableListener {
    private var textureIds = mutableMapOf<Int, Int>()
    private var genTextures = intArrayOf(0)
    private var surfaceTextures = mutableMapOf<Int, SurfaceTexture>()

    fun getTextureIdByTrack(track: Int): Int? {
        return textureIds[track]
    }

    fun register(
        track: Int,
        video: VideoSource,
    ) {
        // 同一軌重複註冊（沒先 unregister）也不能漏：舊的先收乾淨。
        unregister(track)
        GLES20.glGenTextures(1, genTextures, 0)
        val id = genTextures[0]
        textureIds[track] = id
        surfaceTextures[track] =
            SurfaceTexture(id).apply {
                setDefaultBufferSize(
                    video.videoSize.width,
                    video.videoSize.height,
                )
                setOnFrameAvailableListener(this@VideoTextureRegistry)
                video.surface = Surface(this)
            }
    }

    /**
     * obslive.18：原本拿**材質 id** 去刪以**軌道**為 key 的表（`surfaceTextures.remove(id)`）——
     * 幾乎永遠刪不到，SurfaceTexture 從沒 release、GL 材質也沒 glDelete。每次 MediaMixer 停起
     *（開播、握手失敗、關播回待命、斷線重連）每一軌各漏一份。改成依軌道刪、release、刪材質。
     */
    fun unregister(track: Int) {
        surfaceTextures.remove(track)?.let {
            it.setOnFrameAvailableListener(null)
            it.release()
        }
        textureIds.remove(track)?.let { id ->
            genTextures[0] = id
            GLES20.glDeleteTextures(1, genTextures, 0)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        try {
            surfaceTexture?.updateTexImage()
        } catch (e: RuntimeException) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "", e)
            }
        }
    }

    private companion object {
        private val TAG = VideoTextureRegistry::class.java.simpleName
    }
}
