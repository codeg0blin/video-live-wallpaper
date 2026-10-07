package io.github.codeg0blin.videowallpaper

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
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
    private var texMatrixHandle: Int = -1

    // SurfaceTexture's transform for the latest video frame. It carries the
    // video's rotation tag (portrait phone clips are usually stored sideways
    // with a "rotate 90" flag), its vertical flip, and any codec padding
    // crop. Starts as the plain vertical flip, which is what an upright,
    // unpadded video produces, so behaviour before the first frame is
    // unchanged. Column-major, as GL expects.
    private val texMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )
    private var vertexBuffer: java.nio.FloatBuffer? = null

    // GIF support (issue #2). GIF frames arrive as plain ARGB pixels from
    // GifDecoder rather than from MediaPlayer, so they need an ordinary
    // GL_TEXTURE_2D plus a shader that samples a sampler2D instead of
    // samplerExternalOES. Everything here is created lazily on the first
    // uploadGifFrame() call, so video-only users never pay for it and the
    // existing video path above is untouched. Geometry (Fill/Fit) reuses
    // updateGeometry() and its cache variables below.
    private var gifProgram: Int = 0
    private var gifPositionHandle: Int = -1
    private var gifTexCoordHandle: Int = -1
    private var gifTextureUniformHandle: Int = -1
    private var gifTextureId: Int = 0
    private var gifBitmap: Bitmap? = null
    private var gifTextureWidth: Int = 0
    private var gifTextureHeight: Int = 0

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
            texture.getTransformMatrix(texMatrix)
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
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

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
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VIDEO_VERTEX_SHADER_SRC)
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
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        if (positionHandle < 0 || texCoordHandle < 0 || textureUniformHandle < 0 || texMatrixHandle < 0) {
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

    /**
     * Uploads one decoded GIF frame ([argbPixels], row-major, [width] x
     * [height], top row first) into the GIF texture, creating the shader
     * program, texture and staging bitmap on first use and re-creating them
     * if the frame size changes. Does not draw; call [drawGifFrame]
     * afterwards. Must be called on the render thread with our EGL context
     * current. Returns false if GL setup failed (caller should stop using
     * the GIF path rather than retry every frame).
     */
    fun uploadGifFrame(argbPixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return false
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        if (gifProgram == 0 && !createGifProgram()) {
            Log.e(TAG, "Failed to create GIF shader program")
            return false
        }

        val needsAllocation = gifTextureId == 0 || gifBitmap == null ||
            gifTextureWidth != width || gifTextureHeight != height
        if (needsAllocation) {
            releaseGifTexture()
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            if (textures[0] == 0) {
                Log.e(TAG, "glGenTextures returned 0 for GIF texture")
                return false
            }
            gifTextureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gifTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // CLAMP_TO_EDGE and no mipmaps are required for non-power-of-two
            // textures in OpenGL ES 2.0, and GIFs are rarely power-of-two.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            gifBitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory allocating ${width}x$height GIF bitmap", e)
                releaseGifTexture()
                return false
            }
            gifTextureWidth = width
            gifTextureHeight = height
        }

        val bitmap = gifBitmap ?: return false
        bitmap.setPixels(argbPixels, 0, width, 0, 0, width, height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gifTextureId)
        if (needsAllocation) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        return true
    }

    /**
     * Draws the most recently uploaded GIF frame with the current Fill/Fit
     * mode and presents it. Same geometry rules as [drawFrame], using the
     * GIF's own size as the "video" size. Does nothing until
     * [uploadGifFrame] has succeeded at least once. Must be called on the
     * render thread with our EGL context current.
     */
    fun drawGifFrame(viewportWidth: Int, viewportHeight: Int) {
        if (gifProgram == 0 || gifTextureId == 0) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val gifW = gifTextureWidth
        val gifH = gifTextureHeight
        if (gifW != lastVideoWidth || gifH != lastVideoHeight ||
            viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight ||
            scalingMode != lastScalingMode
        ) {
            updateGeometry(viewportWidth, viewportHeight, gifW, gifH)
            lastVideoWidth = gifW
            lastVideoHeight = gifH
            lastViewportWidth = viewportWidth
            lastViewportHeight = viewportHeight
            lastScalingMode = scalingMode
        }

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(gifProgram)

        val buffer = vertexBuffer ?: return
        buffer.position(0)
        GLES20.glVertexAttribPointer(
            gifPositionHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer
        )
        GLES20.glEnableVertexAttribArray(gifPositionHandle)

        buffer.position(2)
        GLES20.glVertexAttribPointer(
            gifTexCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer
        )
        GLES20.glEnableVertexAttribArray(gifTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gifTextureId)
        GLES20.glUniform1i(gifTextureUniformHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(gifPositionHandle)
        GLES20.glDisableVertexAttribArray(gifTexCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Builds the shader program used for GIF frames. Reuses the video
     * program's vertex shader and attribute names; only the fragment shader
     * differs (sampler2D, and forced opaque output).
     */
    private fun createGifProgram(): Boolean {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        if (vertexShader == 0) return false
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, GIF_FRAGMENT_SHADER_SRC)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return false
        }
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return false
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "GIF program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return false
        }
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uni = GLES20.glGetUniformLocation(program, "uTexture")
        if (pos < 0 || tex < 0 || uni < 0) {
            Log.e(TAG, "Failed to get GIF shader attribute/uniform locations")
            GLES20.glDeleteProgram(program)
            return false
        }
        gifProgram = program
        gifPositionHandle = pos
        gifTexCoordHandle = tex
        gifTextureUniformHandle = uni
        return true
    }

    /** Frees the GIF texture and staging bitmap (context must be current). */
    private fun releaseGifTexture() {
        if (gifTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(gifTextureId), 0)
            gifTextureId = 0
        }
        gifBitmap?.recycle()
        gifBitmap = null
        gifTextureWidth = 0
        gifTextureHeight = 0
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
        releaseGifTexture()
        if (gifProgram != 0) {
            GLES20.glDeleteProgram(gifProgram)
            gifProgram = 0
        }
        gifPositionHandle = -1
        gifTexCoordHandle = -1
        gifTextureUniformHandle = -1
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
        positionHandle = -1
        texCoordHandle = -1
        textureUniformHandle = -1
        texMatrixHandle = -1
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

        // Video vertex shader. The quad's texture coordinates are expressed
        // with v = 0 at the top of the picture (see updateGeometry), but
        // SurfaceTexture's matrix expects the usual GL convention with v = 0
        // at the bottom, so v is flipped first and the matrix then applies
        // the video's rotation, flip and crop. For an upright video the two
        // flips cancel and the result is identical to using the coordinates
        // directly, which is what this renderer did before rotation support.
        private const val VIDEO_VERTEX_SHADER_SRC = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord.x, 1.0 - aTexCoord.y, 0.0, 1.0)).xy;
            }
        """

        // GIF frames come from a plain GL_TEXTURE_2D. Output alpha is forced
        // to 1.0 because GIF transparency is binary and we composite onto
        // black: transparent pixels were already stored as rgb 0, and a
        // translucent window surface could otherwise show through.
        private const val GIF_FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);
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