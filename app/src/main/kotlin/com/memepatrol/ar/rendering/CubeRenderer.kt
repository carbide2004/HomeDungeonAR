package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class CubeRenderer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    private val vertexBuffer: FloatBuffer
    private val wireframeIndexBuffer: ShortBuffer
    private val solidIndexBuffer: ShortBuffer

    // Cube size: 0.12m x 0.12m x 0.12m, bottom at y = 0
    init {
        val s = 0.06f
        val h = 0.12f

        val vertices = floatArrayOf(
            -s, 0.0f, -s, // 0: bottom-left-back
             s, 0.0f, -s, // 1: bottom-right-back
             s, 0.0f,  s, // 2: bottom-right-front
            -s, 0.0f,  s, // 3: bottom-left-front
            -s,    h, -s, // 4: top-left-back
             s,    h, -s, // 5: top-right-back
             s,    h,  s, // 6: top-right-front
            -s,    h,  s  // 7: top-left-front
        )

        // 12 edges for wireframe
        val wireframeIndices = shortArrayOf(
            0, 1, 1, 2, 2, 3, 3, 0, // bottom
            4, 5, 5, 6, 6, 7, 7, 4, // top
            0, 4, 1, 5, 2, 6, 3, 7  // pillars
        )

        // 12 triangles (36 indices) for solid faces
        val solidIndices = shortArrayOf(
            // Bottom
            0, 2, 1, 0, 3, 2,
            // Top
            4, 5, 6, 4, 6, 7,
            // Front
            3, 6, 2, 3, 7, 6,
            // Back
            0, 1, 5, 0, 5, 4,
            // Left
            0, 4, 7, 0, 7, 3,
            // Right
            1, 2, 6, 1, 6, 5
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        wireframeIndexBuffer = ByteBuffer.allocateDirect(wireframeIndices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(wireframeIndices)
                position(0)
            }

        solidIndexBuffer = ByteBuffer.allocateDirect(solidIndices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(solidIndices)
                position(0)
            }
    }

    fun createOnGlThread() {
        val vertexShader = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()

        val vs = ShaderUtil.loadGLShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = ShaderUtil.loadGLShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        uMvpMatrix = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
    }

    fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        // 1. Draw semi-transparent solid fill
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform4f(uColor, 0.0f, 0.5f, 0.2f, 0.35f) // Dark terminal green tint
        solidIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, solidIndexBuffer)

        // 2. Draw glowing neon wireframe
        GLES20.glLineWidth(5.0f)
        GLES20.glUniform4f(uColor, 0.0f, 1.0f, 0.4f, 1.0f) // Bright terminal neon green
        wireframeIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_LINES, 24, GLES20.GL_UNSIGNED_SHORT, wireframeIndexBuffer)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(aPosition)
    }
}
