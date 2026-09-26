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

            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                if (u_FilterMode == 0) {
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                    return;
                }

                vec2 uv = v_TexCoord;
                vec2 center = vec2(0.5, 0.5);
                float distFromCenter = length(uv - center);

                // 1. 电磁撕裂抖动 (Horizontal Glitch Slice Jitter)
                float glitchJitter = 0.0;
                if (u_Intensity > 0.12) {
                    float slice = floor(uv.y * 42.0);
                    float sliceSeed = rand(vec2(slice, floor(u_Time * 14.0)));
                    if (sliceSeed > (1.0 - u_Intensity * 0.45)) {
                        glitchJitter = (rand(vec2(slice, u_Time)) - 0.5) * (0.015 + u_Intensity * 0.045);
                    }
                }
                vec2 jitteredUv = uv + vec2(glitchJitter, 0.0);

                // 2. 剧烈异常辐射色散 (Enhanced Chromatic Aberration)
                // 提高基准与非线性放大，高强度下物体轮廓发生肉眼极明显的红蓝错位分离
                float chromMagnitude = 0.005 + (u_Intensity * u_Intensity * 0.065) * (distFromCenter + 0.35);
                vec2 redOffset = (jitteredUv - center) * chromMagnitude;
                vec2 blueOffset = -(jitteredUv - center) * chromMagnitude;

                float r = texture2D(u_Texture, jitteredUv + redOffset).r;
                float g = texture2D(u_Texture, jitteredUv).g;
                float b = texture2D(u_Texture, jitteredUv + blueOffset).b;
                vec3 col = vec3(r, g, b);

                // 3. 终端冷色分级调色 (Terminal Color Grading)
                float gray = dot(col, vec3(0.299, 0.587, 0.114));
                vec3 darkTint = vec3(0.05, 0.14, 0.08); // 墨绿阴影
                vec3 brightTint = vec3(0.82, 0.96, 0.88); // 冷青高光
                vec3 graded = mix(darkTint, brightTint, gray);
                col = mix(col, graded, 0.44);

                // 4. 高频电磁噪波 (Electromagnetic Snow & Noise) - 随异常强度大幅暴增
                float noise = (rand(uv + vec2(u_Time * 0.09, u_Time * 0.17)) - 0.5);
                float noiseAmp = 0.04 + (u_Intensity * u_Intensity) * 0.26;
                col += vec3(noise * noiseAmp);

                // 5. 扫描线效果 (Scanlines)
                float scanline = sin(uv.y * 650.0) * (0.03 + u_Intensity * 0.03);
                col -= vec3(scanline);

                // 6. 空间压迫暗角向内侵蚀 (Vignette Suffocation)
                // 强度越高，暗角收缩越紧，强化被怪谈包裹的窒息感
                float outerRadius = mix(0.82, 0.52, u_Intensity);
                float innerRadius = mix(0.32, 0.12, u_Intensity);
                float vignette = smoothstep(outerRadius, innerRadius, distFromCenter);
                col *= (vignette * 0.88 + 0.12);

                // 7. 高危辐射亮斑微闪 (Radiation Static Flash on critical proximity)
                if (u_Intensity > 0.7) {
                    float flash = step(0.96, rand(vec2(u_Time * 20.0, uv.y)));
                    col += vec3(flash * 0.22);
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
