package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 物理地面标桩渲染器：
 * 贴地六边形底盘 (直径 14cm) + 向上直立的小能量标桩 (高 10cm)
 * 专用于让玩家人肉校验“标桩是否平贴在地砖表面、走动时是否产生滑步漂移”
 */
class GroundPinRenderer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    private val vertexBuffer: FloatBuffer
    private val wireIndexBuffer: ShortBuffer
    private val solidIndexBuffer: ShortBuffer
    private val wireIndexCount: Int
    private val solidIndexCount: Int

    init {
        // 几何体构成：
        // 顶点 0: 棱锥尖顶 (X=0, Y=0.10m, Z=0)
        // 顶点 1: 底面圆心 (X=0, Y=0.001m, Z=0)
        // 顶点 2~7: 贴地六边形顶点 (半径 R=0.07m, Y=0.001m 平平贴于地面)
        val vertices = ArrayList<Float>()
        val wireIndices = ArrayList<Short>()
        val solidIndices = ArrayList<Short>()

        // 0: 尖顶
        vertices.add(0f); vertices.add(0.10f); vertices.add(0f)
        // 1: 底心
        vertices.add(0f); vertices.add(0.001f); vertices.add(0f)

        val segments = 6
        val r = 0.07f
        for (i in 0 until segments) {
            val angle = (i * 2.0 * Math.PI / segments).toFloat()
            val x = r * kotlin.math.cos(angle)
            val z = r * kotlin.math.sin(angle)
            vertices.add(x)
            vertices.add(0.001f) // 贴地 1mm
            vertices.add(z)
        }

        // 构造底盘面三角形 (顶点 1 与周围顶点)
        for (i in 0 until segments) {
            val curr = (i + 2).toShort()
            val next = ((i + 1) % segments + 2).toShort()
            // 底面
            solidIndices.add(1)
            solidIndices.add(curr)
            solidIndices.add(next)

            // 侧棱锥面
            solidIndices.add(0)
            solidIndices.add(curr)
            solidIndices.add(next)

            // 线框: 底圈与立柱
            wireIndices.add(curr); wireIndices.add(next) // 六边形底圈
            wireIndices.add(0); wireIndices.add(curr)    // 尖顶至地面的棱线
        }

        wireIndexCount = wireIndices.size
        solidIndexCount = solidIndices.size

        val vArray = FloatArray(vertices.size) { vertices[it] }
        vertexBuffer = ByteBuffer.allocateDirect(vArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vArray)
                position(0)
            }

        val wArray = ShortArray(wireIndices.size) { wireIndices[it] }
        wireIndexBuffer = ByteBuffer.allocateDirect(wArray.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(wArray)
                position(0)
            }

        val sArray = ShortArray(solidIndices.size) { solidIndices[it] }
        solidIndexBuffer = ByteBuffer.allocateDirect(sArray.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(sArray)
                position(0)
            }
    }

    fun createOnGlThread() {
        val vs = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
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

    fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray) {
        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        // 1. 绘制半透明荧光绿底座
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform4f(uColor, 0.0f, 0.95f, 0.45f, 0.35f)
        solidIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, solidIndexCount, GLES20.GL_UNSIGNED_SHORT, solidIndexBuffer)

        // 2. 绘制亮青绿骨架线框
        GLES20.glLineWidth(4.0f)
        GLES20.glUniform4f(uColor, 0.2f, 1.0f, 0.7f, 1.0f)
        wireIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_LINES, wireIndexCount, GLES20.GL_UNSIGNED_SHORT, wireIndexBuffer)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPosition)
    }
}
