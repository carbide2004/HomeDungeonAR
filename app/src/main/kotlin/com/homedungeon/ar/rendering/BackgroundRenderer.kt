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
            // 关键：采用 highp 避免移动端 GPU (Adreno/Mali) 浮点精度截断导致噪点丢失
            precision highp float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            uniform float u_Time;
            uniform float u_Intensity;
            uniform int u_FilterMode;
            uniform vec2 u_Resolution;

            // 移动端专用抗截断 Hash 算法 (不依赖 sin，在任何高刷屏/移动 GPU 均能稳定产出雪花噪点)
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                if (u_FilterMode == 0) {
                    gl_FragColor = texture2D(u_Texture, v_TexCoord);
                    return;
                }

                vec2 uv = v_TexCoord;
                vec2 center = vec2(0.5, 0.5);
                float distFromCenter = length(uv - center);

                // 1. 显式横向扫描撕裂与信号故障条 (VHS Tape / Radar Glitch Tearing)
                float glitchOffset = 0.0;
                float glitchLineGlow = 0.0;
                if (u_Intensity > 0.08) {
                    // 大切片条带 (低频粗条)
                    float band1 = step(0.91, hash(vec2(floor(uv.y * 16.0), floor(u_Time * 10.0))));
                    // 细切片条带 (高频细条)
                    float band2 = step(0.94, hash(vec2(floor(uv.y * 36.0), floor(u_Time * 18.0))));
                    
                    float displacement = (band1 * 0.06 + band2 * 0.12) * (u_Intensity * 1.5);
                    glitchOffset = displacement;
                    glitchLineGlow = (band1 + band2) * 0.25 * u_Intensity;
                }
                vec2 jitteredUv = uv + vec2(glitchOffset, 0.0);

                // 2. 剧烈异常辐射色散 (Chromatic Aberration)
                float chromMagnitude = 0.006 + (u_Intensity * u_Intensity * 0.075) * (distFromCenter + 0.35);
                vec2 redOffset = (jitteredUv - center) * chromMagnitude;
                vec2 blueOffset = -(jitteredUv - center) * chromMagnitude;

                float r = texture2D(u_Texture, jitteredUv + redOffset).r;
                float g = texture2D(u_Texture, jitteredUv).g;
                float b = texture2D(u_Texture, jitteredUv + blueOffset).b;
                vec3 col = vec3(r, g, b);

                // 3. 终端冷色分级调色 (Terminal Color Grading)
                float gray = dot(col, vec3(0.299, 0.587, 0.114));
                vec3 darkTint = vec3(0.04, 0.14, 0.08); // 墨绿暗部
                vec3 brightTint = vec3(0.82, 0.96, 0.88); // 冷青高光
                vec3 graded = mix(darkTint, brightTint, gray);
                col = mix(col, graded, 0.44);

                // 4. 像素级高频电磁雪花噪波 (基于实际物理分辨率采样，肉眼绝对清晰可见)
                vec2 pixelCoord = uv * u_Resolution;
                float staticNoise = hash(pixelCoord + vec2(u_Time * 123.45, u_Time * 678.90)) - 0.5;
                float noiseAmp = 0.08 + (u_Intensity * 0.35); // 基础噪点 8%，靠近时暴增到 43% 强烈电磁雪花
                col += vec3(staticNoise * noiseAmp);

                // 叠加撕裂处的发光干扰带
                col += vec3(0.0, glitchLineGlow, glitchLineGlow * 0.6);

                // 5. 监视器扫描线 (Scanlines)
                float scanline = sin(uv.y * 650.0) * (0.03 + u_Intensity * 0.03);
                col -= vec3(scanline);

                // 6. 暗角向内收缩 (Vignette Suffocation)
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
