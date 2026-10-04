package io.github.codeg0blin.videowallpaper

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

/**
 * Owns an EGL context/surface bound to a wallpaper's raw Surface, so we can
 * render video frames via OpenGL ES instead of relying on
 * MediaPlayer.setVideoScalingMode() (which is a no-op on WallpaperService
 * surfaces — see issue #1).
 *
 * Chunk 1: EGL plumbing only. No GL drawing yet — this just proves we can
 * stand up a context against the wallpaper surface without crashing.
 *
 * All methods must be called from the same single thread (the dedicated
 * render thread we'll add in a later chunk) — EGL contexts are not
 * thread-safe to share/call across threads without extra work we're not
 * doing here.
 */
class VideoFrameRenderer {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    // Chunk 2: the texture MediaPlayer decodes video frames into, and the
    // Surface wrapper around it that gets passed to MediaPlayer.setSurface().
    // videoTextureId is a GL_TEXTURE_EXTERNAL_OES texture — required for
    // camera/video frames on Android, NOT a normal GL_TEXTURE_2D — and must
    // be created while our EGL context is current.
    private var videoTextureId: Int = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    // Chunk 3: shader program + vertex data for drawing the video texture
    // as a full-screen quad. shaderProgram is a GL program ID (0 = not
    // built yet). vertexBuffer holds interleaved position + texture
    // coordinate data for two triangles covering the full viewport.
    private var shaderProgram: Int = 0
    private var positionHandle: Int = -1
    private var texCoordHandle: Int = -1
    private var textureUniformHandle: Int = -1
    private var vertexBuffer: java.nio.FloatBuffer? = null

    // Chunk 4: Fill/Fit scaling. Geometry is only recomputed when the
    // video size, viewport size, or mode actually changes — not on every
    // frame — since it's the same handful of floats until one of those
    // changes.
    enum class ScalingMode { FILL, FIT }

    private var scalingMode: ScalingMode = ScalingMode.FILL
    private var lastVideoWidth: Int = 0
    private var lastVideoHeight: Int = 0
    private var lastViewportWidth: Int = 0
    private var lastViewportHeight: Int = 0
    private var lastScalingMode: ScalingMode? = null

    // Set when a new frame has arrived and not yet been drawn. Chunk 2 only
    // logs on this; drawing happens in a later chunk. Volatile because
    // onFrameAvailable fires on an arbitrary binder thread, not our render
    // thread.
    @Volatile
    private var frameAvailable: Boolean = false

    /**
     * Optional external hook called whenever a new frame arrives (same
     * thread/timing as the internal frameAvailable flag — i.e. an
     * arbitrary thread, not necessarily the render thread). Set this to
     * schedule a draw exactly when needed instead of polling on a timer.
     * Null is a valid/default state — nothing breaks if it's never set.
     */
    var onFrameAvailableListener: (() -> Unit)? = null

    /**
     * Sets up EGL display/context, and creates a window surface bound to
     * [surface]. Call this from onSurfaceCreated (or when the wallpaper
     * surface is (re)created).
     *
     * Returns true on success. On failure, logs the error and leaves the
     * renderer in a safe, un-initialized state (caller should not attempt
     * to draw).
     */
    fun start(surface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return false
        }

        val config = chooseConfig()
        if (config == null) {
            Log.e(TAG, "eglChooseConfig failed to find a suitable config")
            releaseInternal()
            return false
        }
        eglConfig = config

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            releaseInternal()
            return false
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, surfaceAttribs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed")
            releaseInternal()
            return false
        }

        if (!makeCurrent()) {
            Log.e(TAG, "eglMakeCurrent failed after setup")
            releaseInternal()
            return false
        }

        if (!createVideoTexture()) {
            Log.e(TAG, "Failed to create video texture/SurfaceTexture")
            releaseInternal()
            return false
        }

        if (!createShaderProgram()) {
            Log.e(TAG, "Failed to create shader program")
            releaseInternal()
            return false
        }

        Log.i(TAG, "EGL initialized OK (EGL version ${version[0]}.${version[1]})")
        return true
    }

    /**
     * Creates the GL_TEXTURE_EXTERNAL_OES texture and wraps it in a
     * SurfaceTexture. Must be called with our EGL context current.
     */
    private fun createVideoTexture(): Boolean {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        videoTextureId = textures[0]
        if (videoTextureId == 0) {
            Log.e(TAG, "glGenTextures returned 0")
            return false
        }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture.setOnFrameAvailableListener {
            // Runs on an arbitrary thread per the SurfaceTexture docs, so
            // no GL calls here — just flag internally and notify any
            // external listener (e.g. so a caller can schedule a draw
            // exactly when needed instead of polling on a fixed timer).
            // No logging here deliberately — this fires every single
            // frame for as long as the wallpaper is visible, so a log
            // line here was pure diagnostic overhead (disk/logcat writes
            // dozens of times a second, forever) useful only while
            // confirming the pipeline worked during development.
            frameAvailable = true
            onFrameAvailableListener?.invoke()
        }
        videoSurfaceTexture = surfaceTexture
        videoSurface = Surface(surfaceTexture)
        return true
    }

    /**
     * The Surface to hand to MediaPlayer.setSurface() so decoded frames land
     * in our texture instead of directly on the wallpaper surface. Null
     * until start() has succeeded.
     */
    fun getVideoInputSurface(): Surface? = videoSurface

    /**
     * Sets Fill or Fit scaling mode. Takes effect on the next drawFrame()
     * call. Safe to call from any thread (only touches a plain var read on
     * the render thread), matching how prefs are read elsewhere in this
     * codebase.
     */
    fun setScalingMode(mode: ScalingMode) {
        scalingMode = mode
    }

    /**
     * Draws the current video frame, cropped or letterboxed per the current
     * scaling mode, and presents it. [videoWidth]/[videoHeight] should come
     * from MediaPlayer.getVideoWidth()/getVideoHeight() once available;
     * pass 0 for either before the video is prepared, which falls back to
     * an unscaled full-screen quad (matches chunk 3 behavior) rather than
     * dividing by zero. Must be called on the thread that owns our EGL
     * context, with that context current.
     */
    fun drawFrame(viewportWidth: Int, viewportHeight: Int, videoWidth: Int, videoHeight: Int) {
        val texture = videoSurfaceTexture ?: return
        if (shaderProgram == 0) return

        if (frameAvailable) {
            texture.updateTexImage()
            frameAvailable = false
        }

        if (videoWidth != lastVideoWidth || videoHeight != lastVideoHeight ||
            viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight ||
            scalingMode != lastScalingMode
        ) {
            updateGeometry(viewportWidth, viewportHeight, videoWidth, videoHeight)
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
            lastScalingMode = scalingMode
        }

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(shaderProgram)

        val buffer = vertexBuffer ?: return
        buffer.position(0)
        GLES20.glVertexAttribPointer(
            positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer
        )
        GLES20.glEnableVertexAttribArray(positionHandle)

        buffer.position(2)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(textureUniformHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Recomputes the quad's position and texture-coordinate data for the
     * current scaling mode, video size, and viewport size, and uploads it
     * into vertexBuffer.
     *
     * FIT (letterbox): shrink the quad's on-screen position so its aspect
     * ratio matches the video's, leaving the clear color visible in the
     * margins. Texture coordinates stay full 0..1.
     *
     * FILL (crop): keep the quad full-screen, but shrink the sampled
     * texture-coordinate rect so only a matching-aspect-ratio slice of the
     * video is read, cropping the overflow.
     */
    private fun updateGeometry(viewportWidth: Int, viewportHeight: Int, videoWidth: Int, videoHeight: Int) {
        // Guard against dividing by zero before the video's real size is
        // known yet (e.g. very first frame before onPrepared/getVideoWidth
        // has anything meaningful) — fall back to an unscaled full quad.
        if (videoWidth <= 0 || videoHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            setQuad(-1f, -1f, 1f, 1f, 0f, 1f, 1f, 0f)
            return
        }

        val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        when (scalingMode) {
            ScalingMode.FIT -> {
                // Shrink position (letterbox), full texture coords.
                var posX = 1f
                var posY = 1f
                if (videoAspect > viewportAspect) {
                    // Video relatively wider than viewport -> bars top/bottom.
                    posY = viewportAspect / videoAspect
                } else {
                    // Video relatively taller than viewport -> bars left/right.
                    posX = videoAspect / viewportAspect
                }
                setQuad(-posX, -posY, posX, posY, 0f, 1f, 1f, 0f)
            }
            ScalingMode.FILL -> {
                // Full-screen position, cropped texture coords.
                var u0 = 0f
                var u1 = 1f
                var v0 = 0f
                var v1 = 1f
                if (videoAspect > viewportAspect) {
                    // Video relatively wider than viewport -> crop left/right.
                    val visibleFraction = viewportAspect / videoAspect
                    val margin = (1f - visibleFraction) / 2f
                    u0 = margin
                    u1 = 1f - margin
                } else {
                    // Video relatively taller than viewport -> crop top/bottom.
                    val visibleFraction = videoAspect / viewportAspect
                    val margin = (1f - visibleFraction) / 2f
                    v0 = margin
                    v1 = 1f - margin
                }
                setQuad(-1f, -1f, 1f, 1f, u0, v1, u1, v0)
            }
        }
    }

    /**
     * Writes quad vertex data for a rectangle from (posX0, posY0) to
     * (posX1, posY1) in clip space, sampling texture coordinates from
     * (u0, vTop) to (u1, vBottom). Matches the same 4-vertex triangle-strip
     * layout and v-flip convention established in chunk 3.
     */
    private fun setQuad(posX0: Float, posY0: Float, posX1: Float, posY1: Float, u0: Float, vTop: Float, u1: Float, vBottom: Float) {
        val quad = floatArrayOf(
            posX0, posY0, u0, vTop,
            posX1, posY0, u1, vTop,
            posX0, posY1, u0, vBottom,
            posX1, posY1, u1, vBottom
        )
        val buffer = vertexBuffer
        if (buffer == null || buffer.capacity() < quad.size) {
            val bb = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
            bb.order(java.nio.ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer?.apply {
            position(0)
            put(quad)
            position(0)
        }
    }

    /**
     * Compiles the vertex/fragment shaders, links the program, and sets up
     * a full-screen quad's vertex data (two triangles as a strip: position
     * xy + texture coordinate uv, interleaved).
     */
    private fun createShaderProgram(): Boolean {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        if (vertexShader == 0) return false
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return false
        }

        val program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "glCreateProgram failed")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return false
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        // Shaders are no longer needed standalone once linked into the
        // program — safe to delete regardless of link success.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return false
        }

        shaderProgram = program
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureUniformHandle = GLES20.glGetUniformLocation(program, "uTexture")
        if (positionHandle < 0 || texCoordHandle < 0 || textureUniformHandle < 0) {
            Log.e(TAG, "Failed to get shader attribute/uniform locations")
            return false
        }

        // Full-screen quad as a triangle strip: x, y, u, v per vertex.
        // Positions in clip space (-1..1), texture coords 0..1 with v
        // flipped (0 at top) to match typical video frame orientation.
        val quad = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
        val bb = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
        bb.order(java.nio.ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer().apply {
            put(quad)
            position(0)
        }

        return true
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type $type")
            return 0
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /** Makes this renderer's EGL context/surface current on the calling thread. */
    fun makeCurrent(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    /** Tears down all EGL resources. Call from onSurfaceDestroyed. */
    fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        videoSurface?.release()
        videoSurface = null
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (videoTextureId != 0) {
            // Deleting the texture requires our EGL context to still be
            // current — do it before eglDestroyContext below.
            GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
            videoTextureId = 0
        }
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        positionHandle = -1
        texCoordHandle = -1
        textureUniformHandle = -1
        vertexBuffer = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        eglConfig = null
    }

    private fun chooseConfig(): EGLConfig? {
        val attribList = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val success = EGL14.eglChooseConfig(
            eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0
        )
        if (!success || numConfigs[0] <= 0) return null
        return configs[0]
    }

    companion object {
        private const val TAG = "VideoFrameRenderer"

        // 4 floats per vertex (x, y, u, v), 4 bytes per float.
        private const val STRIDE_BYTES = 4 * 4

        // #extension directive is required per the SurfaceTexture docs when
        // sampling a samplerExternalOES texture from a GLES2 shader.
        private const val VERTEX_SHADER_SRC = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}