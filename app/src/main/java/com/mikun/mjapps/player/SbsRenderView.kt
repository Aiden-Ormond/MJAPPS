package com.mikun.mjapps.player

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.mikun.mjapps.data.SbsVideoConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 渲染解析模式 */
enum class RenderMode(val label: String) {
    /** Alpha + RGB 合成（默认，带透明度） */
    COMPOSITE("合成"),

    /** 仅显示 Alpha 遮罩（灰度） */
    ALPHA("Alpha"),

    /** 仅显示 RGB 彩色帧 */
    RGB("RGB"),

    /** 原始 SbS 整帧（左 Alpha 右 RGB 并排） */
    RAW_SBS("原始SbS"),
}

/**
 * Alpha SbS 视频渲染视图。
 *
 * MediaCodec 解码输出到 GL_TEXTURE_EXTERNAL_OES，片元着色器按 config 中
 * aFrame / rgbFrame 的像素矩形分别采样：RGB 半边提供颜色，Alpha 半边亮度提供透明度，
 * 输出预乘 Alpha 的合成画面。视图本身透明（棋盘背景可透出）。
 */
class SbsRenderView(
    context: Context,
    val config: SbsVideoConfig,
) : GLSurfaceView(context) {

    var player: AlphaSbsPlayer? = null

    var mode: RenderMode = RenderMode.COMPOSITE
        set(value) {
            field = value
            requestRender()
        }

    private val renderer = SbsRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private inner class SbsRenderer : Renderer {

        private var program = 0
        private var aPosLoc = 0
        private var aUvLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uARectLoc = 0
        private var uRgbRectLoc = 0
        private var uVideoSizeLoc = 0
        private var uModeLoc = 0

        private var texId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private val texMatrix = FloatArray(16)
        private var viewW = 1
        private var viewH = 1

        // TRIANGLE_STRIP 四顶点，每顶点 (x, y, u, v)，逐帧更新
        private val vertexData = FloatArray(16)
        private val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            releaseCodecSurface()
            program = buildProgram()
            aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
            aUvLoc = GLES20.glGetAttribLocation(program, "aUV")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uARectLoc = GLES20.glGetUniformLocation(program, "uARect")
            uRgbRectLoc = GLES20.glGetUniformLocation(program, "uRgbRect")
            uVideoSizeLoc = GLES20.glGetUniformLocation(program, "uVideoSize")
            uModeLoc = GLES20.glGetUniformLocation(program, "uMode")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(this@SbsRenderView.config.videoWidth, this@SbsRenderView.config.videoHeight)
            st.setOnFrameAvailableListener({ requestRender() }, Handler(Looper.getMainLooper()))
            surfaceTexture = st
            val s = Surface(st)
            surface = s
            player?.start(s)
            requestRender()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
            requestRender()
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val st = surfaceTexture ?: return
            try {
                st.updateTexImage()
            } catch (e: Exception) {
                return
            }
            st.getTransformMatrix(texMatrix)

            val m = mode
            val contentW: Float
            val contentH: Float
            if (m == RenderMode.RAW_SBS) {
                contentW = config.videoWidth.toFloat()
                contentH = config.videoHeight.toFloat()
            } else {
                contentW = config.width.toFloat()
                contentH = config.height.toFloat()
            }

            // letterbox 适配
            val viewAspect = viewW.toFloat() / viewH.toFloat()
            val contentAspect = contentW / contentH
            val qw: Float
            val qh: Float
            if (viewAspect > contentAspect) {
                qh = 1f
                qw = contentAspect / viewAspect
            } else {
                qw = 1f
                qh = viewAspect / contentAspect
            }

            vertexData[0] = -qw; vertexData[1] = -qh; vertexData[2] = 0f; vertexData[3] = 0f
            vertexData[4] = qw;  vertexData[5] = -qh; vertexData[6] = 1f; vertexData[7] = 0f
            vertexData[8] = -qw; vertexData[9] = qh;  vertexData[10] = 0f; vertexData[11] = 1f
            vertexData[12] = qw; vertexData[13] = qh; vertexData[14] = 1f; vertexData[15] = 1f
            vertexBuffer.position(0)
            vertexBuffer.put(vertexData)
            vertexBuffer.position(0)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform4f(
                uARectLoc,
                config.alphaFrame.x.toFloat(),
                config.alphaFrame.y.toFloat(),
                config.alphaFrame.w.toFloat(),
                config.alphaFrame.h.toFloat(),
            )
            GLES20.glUniform4f(
                uRgbRectLoc,
                config.rgbFrame.x.toFloat(),
                config.rgbFrame.y.toFloat(),
                config.rgbFrame.w.toFloat(),
                config.rgbFrame.h.toFloat(),
            )
            GLES20.glUniform2f(
                uVideoSizeLoc,
                config.videoWidth.toFloat(),
                config.videoHeight.toFloat(),
            )
            GLES20.glUniform1i(uModeLoc, m.ordinal)

            GLES20.glEnableVertexAttribArray(aPosLoc)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aUvLoc)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aUvLoc)
        }

        private fun releaseCodecSurface() {
            try {
                surface?.release()
            } catch (_: Exception) {
            }
            try {
                surfaceTexture?.release()
            } catch (_: Exception) {
            }
            surface = null
            surfaceTexture = null
        }

        private fun buildProgram(): Int {
            val vs = """
                attribute vec2 aPos;
                attribute vec2 aUV;
                varying vec2 vUV;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vUV = aUV;
                }
            """.trimIndent()

            val fs = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vUV;
                uniform samplerExternalOES uTex;
                uniform mat4 uTexMatrix;
                // aFrame / rgbFrame 矩形（x, y, w, h，视频像素坐标，原点左上）
                uniform vec4 uARect;
                uniform vec4 uRgbRect;
                uniform vec2 uVideoSize;
                // 0: 合成  1: Alpha  2: RGB  3: 原始 SbS
                uniform int uMode;

                vec2 tx(vec2 uv) {
                    return (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                }

                // 像素坐标(左上原点) -> GL 纹理坐标(左下原点) -> 经 SurfaceTexture 矩阵变换
                vec2 pxToTex(vec2 px) {
                    return tx(vec2(px.x / uVideoSize.x, 1.0 - px.y / uVideoSize.y));
                }

                void main() {
                    vec4 color;
                    if (uMode == 3) {
                        // 原始整帧
                        color = vec4(texture2D(uTex, tx(vUV)).rgb, 1.0);
                    } else {
                        // vUV 为内容区归一化坐标（GL 习惯：左下原点）
                        vec2 rgbPx = uRgbRect.xy + vec2(vUV.x * uRgbRect.z, (1.0 - vUV.y) * uRgbRect.w);
                        vec3 rgb = texture2D(uTex, pxToTex(rgbPx)).rgb;
                        if (uMode == 2) {
                            // 仅 RGB
                            color = vec4(rgb, 1.0);
                        } else {
                            vec2 aPx = uARect.xy + vec2(vUV.x * uARect.z, (1.0 - vUV.y) * uARect.w);
                            float a = texture2D(uTex, pxToTex(aPx)).r;
                            if (uMode == 1) {
                                // 仅 Alpha（灰度）
                                color = vec4(vec3(a), 1.0);
                            } else {
                                // Alpha 合成（预乘输出）
                                color = vec4(rgb * a, a);
                            }
                        }
                    }
                    gl_FragColor = color;
                }
            """.trimIndent()

            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vShader)
            GLES20.glAttachShader(p, fShader)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "program link failed: " + GLES20.glGetProgramInfoLog(p) }
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
            return p
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
    }
}
