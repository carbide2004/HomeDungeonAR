package com.homedungeon.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 纯物理贴地圆环渲染器：严格垂直于重力、紧密贴合物理地表
 */
class GroundReticleRenderer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    private val vertexBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer
    private val indexCount: Int

    init {
        val segments = 40
        val radius = 0.32f
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Short>()

        vertices.add(0f); vertices.add(0.002f); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (i * 2.0 * Math.PI / segments).toFloat()
            val x = radius * kotlin.math.cos(angle)
            val z = radius * kotlin.math.sin(angle)
            vertices.add(x)
            vertices.add(0.002f)
            vertices.add(z)

            if (i > 0) {
                indices.add(0)
                indices.add(i.toShort())
                indices.add((i + 1).toShort())
            }
        }
        indexCount = indices.size

        val vArray = FloatArray(vertices.size) { vertices[it] }
        vertexBuffer = ByteBuffer.allocateDirect(vArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vArray)
                position(0)
            }

        val iArray = ShortArray(indices.size) { indices[it] }
        indexBuffer = ByteBuffer.allocateDirect(iArray.size * 2)
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
            varying vec2 v_Pos;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_Pos = a_Position.xz;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec2 v_Pos;
            uniform vec4 u_Color;
            void main() {
                float dist = length(v_Pos) / 0.32;
                // 科技感双环扫描光标
                float outerRing = smoothstep(0.78, 0.88, dist) - smoothstep(0.96, 1.0, dist);
                float centerDot = 1.0 - smoothstep(0.0, 0.08, dist);
                float alpha = clamp(outerRing + centerDot * 0.8, 0.0, 1.0);
                gl_FragColor = vec4(u_Color.rgb, alpha * u_Color.a);
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
    }

    /**
     * 在世界坐标 (x, groundY, z) 处绘制绝对水平贴地的发光圆环
     */
    fun drawAtGroundPosition(
        groundX: Float,
        groundY: Float,
        groundZ: Float,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        val modelMatrix = FloatArray(16)
        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        // 构造纯水平重力对齐变换矩阵 (旋转为单位阵，绝对平行于物理地面，杜绝任何上翘下斜)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, groundX, groundY, groundZ)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColor, 0.0f, 1.0f, 0.65f, 0.90f)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
