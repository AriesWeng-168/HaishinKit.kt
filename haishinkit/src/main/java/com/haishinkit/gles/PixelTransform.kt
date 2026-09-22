package com.haishinkit.gles

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.util.Log
import android.util.Size
import android.view.Choreographer
import android.view.Surface
import com.haishinkit.BuildConfig
import com.haishinkit.graphics.FpsController
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.ScheduledFpsController
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.lang.Running
import com.haishinkit.screen.NullRenderer
import com.haishinkit.screen.Renderer
import com.haishinkit.screen.Screen
import com.haishinkit.screen.VideoScreenObject
import java.util.concurrent.atomic.AtomicBoolean

internal class PixelTransform(
    override val context: Context,
) : PixelTransform,
    Running,
    Choreographer.FrameCallback {
    override val isRunning: AtomicBoolean = AtomicBoolean(false)
    override var screen: Screen? = null
        set(value) {
            if (value == field) return
            if (field != null) {
                stopRunning()
            }
            field = value
            if (value == null) {
                stopRunning()
            } else {
                startRunning()
            }
        }
    override var surface: Surface? = null
        set(value) {
            if (value == field) return
            field = value
            if (value == null) {
                stopRunning()
            } else {
                startRunning()
            }
        }

    override var videoGravity: VideoGravity
        get() {
            return video.videoGravity
        }
        set(value) {
            video.videoGravity = value
        }

    override var imageExtent = Size(0, 0)
        set(value) {
            if (field == value) return
            field = value
            GLES20.glViewport(
                0,
                0,
                value.width,
                value.height,
            )
            video.frame.set(0, 0, value.width, value.height)
            video.invalidateLayout()
        }

    override var videoEffect: VideoEffect = BicubicVideoEffect()
        set(value) {
            if (field == value) return
            field = value
            program = shaderLoader.getProgram(GLES20.GL_TEXTURE_2D, value)
        }

    override var frameRate: Int
        get() = fpsController.frameRate
        set(value) {
            fpsController.frameRate = value
        }

    override var backgroundColor: Int = Color.BLACK

    private val graphicsContext: GraphicsContext by lazy { GraphicsContext() }
    private var choreographer: Choreographer? = null
        set(value) {
            field?.removeFrameCallback(this)
            field = value
            field?.postFrameCallback(this)
        }
    private var program: Program? = null
        set(value) {
            field?.dispose()
            field = value
        }
    private val shaderLoader by lazy {
        ShaderLoader(context)
    }
    private val video: VideoScreenObject by lazy { VideoScreenObject(target = GLES20.GL_TEXTURE_2D) }
    private val renderer: Renderer by lazy { NullRenderer.SHARED }
    private val fpsController: FpsController by lazy { ScheduledFpsController() }

    override fun startRunning() {
        if (isRunning.get()) return
        val surface = surface ?: return
        if (screen == null) return
        // obslive.17: setSurface 是經 handler 排隊後才執行的；SurfaceView 可能在排隊期間就 destroy 了
        //（快速切背景／視窗還沒 attach），這時 eglCreateWindowSurface 會丟
        // IllegalArgumentException("Make sure the SurfaceView ... has a valid Surface") 直接殺掉
        // ThreadPixelTransform → 整個 App 閃退（2026-09-22 模擬器實錘）。先驗 isValid，建不起來就當
        // 沒有 surface：下一次 surfaceCreated 會帶一顆新的 Surface 再來。
        if (!surface.isValid) {
            Log.w(TAG, "startRunning(): surface is not valid yet, waiting for the next one")
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "startRunning()")
        }
        isRunning.set(true)
        video.videoGravity = videoGravity
        try {
            graphicsContext.apply {
                open((screen as? com.haishinkit.gles.screen.ThreadScreen)?.graphicsContext)
                makeCurrent(createWindowSurface(surface))
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "startRunning(): could not create the EGL window surface", e)
            runCatching { graphicsContext.close() }
            isRunning.set(false)
            return
        }
        program = shaderLoader.getProgram(GLES20.GL_TEXTURE_2D, videoEffect)
        screen?.let {
            video.videoSize = Size(it.bounds.width(), it.bounds.height())
        }
        fpsController.clear()
        choreographer = Choreographer.getInstance()
    }

    override fun stopRunning() {
        if (!isRunning.get()) return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "stopRunning()")
        }
        clearColor(backgroundColor)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        graphicsContext.swapBuffers()
        program = null
        choreographer = null
        shaderLoader.release()
        graphicsContext.close()
        isRunning.set(false)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isRunning.get()) {
            choreographer?.postFrameCallback(this)
        }
        if (frameTimeNanos <= 0L || surface == null) {
            return
        }
        val screen = screen ?: return
        var timestamp = frameTimeNanos
        if (fpsController.advanced(timestamp)) {
            timestamp = fpsController.timestamp(timestamp)
            try {
                clearColor(backgroundColor)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                if (video.videoSize.width != screen.frame.width() || video.videoSize.height != screen.frame.height()) {
                    video.videoSize = Size(screen.frame.width(), screen.frame.height())
                }
                if (video.shouldInvalidateLayout) {
                    video.textureId = screen.textureId
                    video.layout(renderer)
                }
                program?.use()
                program?.bind(videoEffect)
                program?.draw(video)
                graphicsContext.setPresentationTime(timestamp)
                graphicsContext.swapBuffers()
            } catch (e: RuntimeException) {
                Log.e(TAG, "", e)
            }
        }
    }

    fun clearColor(color: Int) {
        GLES20.glClearColor(
            ((color shr 16) and 0xFF) / 255.0f,
            ((color shr 8) and 0xFF) / 255.0f,
            (color and 0xFF) / 255.0f,
            ((color shr 24) and 0xFF) / 255.0f,
        )
    }

    companion object {
        private val TAG = PixelTransform::class.java.simpleName
    }
}
