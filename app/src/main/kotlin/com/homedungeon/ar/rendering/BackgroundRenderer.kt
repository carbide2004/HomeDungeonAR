package com.homedungeon.ar.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0
    private var uTimeUniform = 0
    private var uIntensityUniform = 0
    private var uFilterModeUniform = 0
    private var uResolutionUniform = 0

    var textureId = -1
        private set

    private val quadCoordsBuffer: FloatBuffer
    private val transformedTexCoordsBuffer: FloatBuffer

    init {
        val quadCoords = floatArrayOf(
            -1.0f, -1.0f,
            +1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, +1.0f
        )
        quadCoordsBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadCoords)
                position(0)
            }

        transformedTexCoordsBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Time;
            uniform float u_Intensity;
            uniform int u_FilterMode;
            uniform vec2 u_Resolution;

            // 伪随机生成高频噪点
            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                if (u_FilterMode == 0) {
                    // 直通模式
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                    return;
                }

                vec2 uv = v_TexCoord;
                vec2 center = vec2(0.5, 0.5);
                float distFromCenter = length(uv - center);

                // 1. 异常辐射色散 (Chromatic Aberration) - 越靠近异常，边缘色散越明显
                float chromAberr = 0.003 + u_Intensity * 0.015 * distFromCenter;
                vec2 redOffset = (uv - center) * chromAberr;
                vec2 blueOffset = -(uv - center) * chromAberr;

                float r = texture2D(u_Texture, uv + redOffset).r;
                float g = texture2D(u_Texture, uv).g;
                float b = texture2D(u_Texture, uv + blueOffset).b;
                vec3 col = vec3(r, g, b);

                // 2. 终端冷峻调色 (冷调青灰 / 墨绿阴影)
                float gray = dot(col, vec3(0.299, 0.587, 0.114));
                vec3 darkTint = vec3(0.06, 0.13, 0.09); // 墨绿暗部
                vec3 brightTint = vec3(0.85, 0.96, 0.89); // 冷青亮部
                vec3 graded = mix(darkTint, brightTint, gray);
                col = mix(col, graded, 0.42); // 混合 42% 终端色调，保留房间轮廓同时营造阴森感

                // 3. 动态高感光电子噪点 (Grain)
                float noise = (rand(uv + vec2(u_Time * 0.07, u_Time * 0.13)) - 0.5);
                float noiseAmp = 0.05 + u_Intensity * 0.09;
                col += vec3(noise * noiseAmp);

                // 4. 监视器微弱扫描线 (Scanlines)
                float scanline = sin(uv.y * 700.0) * 0.025;
                col -= vec3(scanline);

                // 5. 光学暗角 (Vignette)
                float vignette = smoothstep(0.80, 0.28, distFromCenter);
                col *= (vignette * 0.85 + 0.15);

                // 6. 异常靠近时的信号闪烁撕裂微震 (Glitch on high intensity)
                if (u_Intensity > 0.6) {
                    float glitchBar = step(0.98, sin(uv.y * 30.0 + u_Time * 15.0));
                    col += vec3(glitchBar * 0.12);
                }

                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val vs = ShaderUtil.loadGLShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = ShaderUtil.loadGLShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")

        uTimeUniform = GLES20.glGetUniformLocation(program, "u_Time")
        uIntensityUniform = GLES20.glGetUniformLocation(program, "u_Intensity")
        uFilterModeUniform = GLES20.glGetUniformLocation(program, "u_FilterMode")
        uResolutionUniform = GLES20.glGetUniformLocation(program, "u_Resolution")
    }

    fun draw(
        frame: Frame,
        timeSeconds: Float = 0f,
        intensity: Float = 0f,
        filterEnabled: Boolean = true,
        viewportWidth: Int = 1080,
        viewportHeight: Int = 2400
    ) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoordsBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoordsBuffer
            )
        }

        if (frame.timestamp == 0L) {
            return
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        // Set Post-processing uniforms
        GLES20.glUniform1f(uTimeUniform, timeSeconds)
        GLES20.glUniform1f(uIntensityUniform, intensity)
        GLES20.glUniform1i(uFilterModeUniform, if (filterEnabled) 1 else 0)
        GLES20.glUniform2f(uResolutionUniform, viewportWidth.toFloat(), viewportHeight.toFloat())

        quadCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoordsBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        transformedTexCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoordsBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}
