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
    private var uFrameUniform = 0
    private var uIntensityUniform = 0
    private var uFilterModeUniform = 0

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
            precision highp float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Frame;
            uniform float u_Intensity;
            uniform int u_FilterMode;

            // 行业标准 Interleaved Gradient Noise (IGN - 由 Jorge Jimenez 提出，广泛用于 3A 主机与移动端)
            // 绝无浮点溢出，逐物理像素点高频采样，噪点颗粒感极强且平稳
            float ign(vec2 pixelPos, float frame) {
                vec2 p = pixelPos + vec2(frame * 5.588238, frame * 3.141592);
                float f = 0.06711056 * p.x + 0.00583715 * p.y;
                return fract(52.9829189 * fract(f));
            }

            void main() {
                if (u_FilterMode == 0) {
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                    return;
                }

                vec2 uv = v_TexCoord;
                vec2 center = vec2(0.5, 0.5);
                float distFromCenter = length(uv - center);

                // 1. 异常辐射色散 (Chromatic Aberration) - 靠近异常时红蓝色散加剧
                float chromMagnitude = 0.006 + (u_Intensity * u_Intensity * 0.070) * (distFromCenter + 0.35);
                vec2 redOffset = (uv - center) * chromMagnitude;
                vec2 blueOffset = -(uv - center) * chromMagnitude;

                float r = texture2D(u_Texture, uv + redOffset).r;
                float g = texture2D(u_Texture, uv).g;
                float b = texture2D(u_Texture, uv + blueOffset).b;
                vec3 col = vec3(r, g, b);

                // 2. 终端冷色分级调色 (Terminal Color Grading)
                float gray = dot(col, vec3(0.299, 0.587, 0.114));
                vec3 darkTint = vec3(0.04, 0.14, 0.08); // 墨绿暗部
                vec3 brightTint = vec3(0.82, 0.96, 0.88); // 冷青高光
                vec3 graded = mix(darkTint, brightTint, gray);
                col = mix(col, graded, 0.44);

                // 3. 像素级高频电磁雪花噪波 (基于屏幕硬件实际物理像素坐标 gl_FragCoord.xy)
                // 基础噪点 10%，逼近异常时暴增至 35% 强噪波
                float rawNoise = ign(gl_FragCoord.xy, u_Frame) - 0.5;
                float noiseStrength = 0.10 + (u_Intensity * 0.25);
                col += vec3(rawNoise * noiseStrength);

                // 4. 监视器扫描线 (Scanlines)
                float scanline = sin(uv.y * 650.0) * (0.025 + u_Intensity * 0.025);
                col -= vec3(scanline);

                // 5. 暗角向内收缩 (Vignette Suffocation) - 靠近异常时暗圈内聚压迫
                float outerRadius = mix(0.82, 0.52, u_Intensity);
                float innerRadius = mix(0.32, 0.12, u_Intensity);
                float vignette = smoothstep(outerRadius, innerRadius, distFromCenter);
                col *= (vignette * 0.88 + 0.12);

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

        uFrameUniform = GLES20.glGetUniformLocation(program, "u_Frame")
        uIntensityUniform = GLES20.glGetUniformLocation(program, "u_Intensity")
        uFilterModeUniform = GLES20.glGetUniformLocation(program, "u_FilterMode")
    }

    fun draw(
        frame: Frame,
        frameIndex: Float = 0f,
        intensity: Float = 0f,
        filterEnabled: Boolean = true
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

        // Set uniforms
        GLES20.glUniform1f(uFrameUniform, frameIndex)
        GLES20.glUniform1f(uIntensityUniform, intensity)
        GLES20.glUniform1i(uFilterModeUniform, if (filterEnabled) 1 else 0)

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
