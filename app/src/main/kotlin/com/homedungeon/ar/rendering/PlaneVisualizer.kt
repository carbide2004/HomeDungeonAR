package com.homedungeon.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PlaneVisualizer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

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

    /**
     * 仅渲染通过严格过滤后的主地面平面，并支持强行修正为触地校准高度
     */
    fun drawMainFloor(
        mainFloor: Plane?,
        calibratedFloorY: Float?,
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

        // 若玩家进行了物理触地校准，将矩阵的 Y 轴平移高度严格对齐到真实触地零点
        if (calibratedFloorY != null) {
            modelMatrix[13] = calibratedFloorY
        }

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

        val vertexArray = FloatArray(pointCount * 3)
        for (i in 0 until pointCount) {
            vertexArray[i * 3 + 0] = polygon.get(i * 2 + 0)
            vertexArray[i * 3 + 1] = 0.002f // 略微上浮 2mm 避免与真实地板闪烁
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

        // 填充半透明终端荧光绿
        GLES20.glUniform4f(uColor, 0.0f, 0.95f, 0.45f, 0.24f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, pointCount)

        // 勾勒高对比度边框轮廓
        GLES20.glLineWidth(5.0f)
        GLES20.glUniform4f(uColor, 0.2f, 1.0f, 0.6f, 0.85f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, pointCount)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
