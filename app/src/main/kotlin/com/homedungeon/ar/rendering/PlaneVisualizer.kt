package com.homedungeon.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class PlaneVisualizer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    // 贴地准星 (Ground Reticle) 渲染管线
    private var reticleProgram = 0
    private var reticleMvp = 0
    private var reticleColor = 0
    private var reticlePos = 0

    private val reticleVertexBuffer: FloatBuffer
    private val reticleIndexBuffer: ShortBuffer
    private val reticleIndexCount: Int

    init {
        // 构建地面圆形瞄准光环几何体 (半径 0.28m, 32 个分段)
        val segments = 32
        val radius = 0.28f
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Short>()

        vertices.add(0f); vertices.add(0.003f); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (i * 2.0 * Math.PI / segments).toFloat()
            val x = radius * kotlin.math.cos(angle)
            val z = radius * kotlin.math.sin(angle)
            vertices.add(x)
            vertices.add(0.003f)
            vertices.add(z)

            if (i > 0) {
                indices.add(0)
                indices.add(i.toShort())
                indices.add((i + 1).toShort())
            }
        }
        reticleIndexCount = indices.size

        val vArray = FloatArray(vertices.size) { vertices[it] }
        reticleVertexBuffer = ByteBuffer.allocateDirect(vArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vArray)
                position(0)
            }

        val iArray = ShortArray(indices.size) { indices[it] }
        reticleIndexBuffer = ByteBuffer.allocateDirect(iArray.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(iArray)
                position(0)
            }
    }

    fun createOnGlThread() {
        val vs = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            varying vec2 v_LocalPos;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_LocalPos = a_Position.xz;
            }
        """.trimIndent()

        // 兼容所有 Android OpenGL ES 2.0 GPU，不依赖任何扩展指令
        val fs = """
            precision mediump float;
            varying vec2 v_LocalPos;
            uniform vec4 u_Color;
            void main() {
                // 经典三角波生成细腻网格线 (每隔 0.2m 一条网线)
                vec2 grid = abs(sin(v_LocalPos * 15.707963));
                float line = step(0.92, max(grid.x, grid.y));
                
                // 柔和边缘羽化渐隐
                float dist = length(v_LocalPos);
                float alpha = (1.0 - smoothstep(0.4, 2.0, dist)) * u_Color.a;
                
                vec3 finalCol = mix(u_Color.rgb * 0.7, vec3(0.0, 1.0, 0.6), line * 0.85);
                gl_FragColor = vec4(finalCol, alpha * (0.35 + line * 0.65));
            }
        """.trimIndent()

        val vShader = ShaderUtil.loadGLShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = ShaderUtil.loadGLShader(GLES20.GL_FRAGMENT_SHADER, fs)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }

        uMvpMatrix = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")

        // 2. 贴地圆环光标 Shader
        val rvs = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            varying vec2 v_Pos;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_Pos = a_Position.xz;
            }
        """.trimIndent()

        val rfs = """
            precision mediump float;
            varying vec2 v_Pos;
            uniform vec4 u_Color;
            void main() {
                float dist = length(v_Pos) / 0.28;
                float ring = smoothstep(0.70, 0.88, dist) - smoothstep(0.96, 1.0, dist);
                gl_FragColor = vec4(u_Color.rgb, ring * u_Color.a);
            }
        """.trimIndent()

        val rvShader = ShaderUtil.loadGLShader(GLES20.GL_VERTEX_SHADER, rvs)
        val rfShader = ShaderUtil.loadGLShader(GLES20.GL_FRAGMENT_SHADER, rfs)

        reticleProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, rvShader)
            GLES20.glAttachShader(it, rfShader)
            GLES20.glLinkProgram(it)
        }

        reticleMvp = GLES20.glGetUniformLocation(reticleProgram, "u_MvpMatrix")
        reticleColor = GLES20.glGetUniformLocation(reticleProgram, "u_Color")
        reticlePos = GLES20.glGetAttribLocation(reticleProgram, "a_Position")
    }

    fun drawMainFloor(
        mainFloor: Plane?,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ) {
        if (mainFloor == null || mainFloor.trackingState != TrackingState.TRACKING) {
            return
        }

        val polygon = mainFloor.polygon
        val pointCount = polygon.limit() / 2
        if (pointCount < 3) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        val modelMatrix = FloatArray(16)
        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        mainFloor.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

        val vertexArray = FloatArray(pointCount * 3)
        for (i in 0 until pointCount) {
            vertexArray[i * 3 + 0] = polygon.get(i * 2 + 0)
            vertexArray[i * 3 + 1] = 0.001f
            vertexArray[i * 3 + 2] = polygon.get(i * 2 + 1)
        }

        val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexArray)
                position(0)
            }

        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        GLES20.glUniform4f(uColor, 0.0f, 0.85f, 0.45f, 0.35f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, pointCount)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun drawGroundReticle(
        reticleMatrix: FloatArray,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(reticleProgram)

        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, reticleMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUniformMatrix4fv(reticleMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(reticleColor, 0.0f, 1.0f, 0.6f, 0.85f)

        reticleVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(reticlePos, 3, GLES20.GL_FLOAT, false, 0, reticleVertexBuffer)
        GLES20.glEnableVertexAttribArray(reticlePos)

        reticleIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, reticleIndexCount, GLES20.GL_UNSIGNED_SHORT, reticleIndexBuffer)

        GLES20.glDisableVertexAttribArray(reticlePos)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
