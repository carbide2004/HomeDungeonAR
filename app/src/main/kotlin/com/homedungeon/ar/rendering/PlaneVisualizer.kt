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

    fun drawPlanes(
        planes: Collection<Plane>,
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

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            val polygon = plane.polygon
            val pointCount = polygon.limit() / 2
            if (pointCount < 3) continue

            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)
            GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

            // ARCore 凸包坐标: (x, z)，原点在 centerPose。我们转为三维坐标 (x, 0, z)
            val vertexArray = FloatArray(pointCount * 3)
            for (i in 0 until pointCount) {
                vertexArray[i * 3 + 0] = polygon.get(i * 2 + 0)
                vertexArray[i * 3 + 1] = 0.002f // 略微浮起 2mm 避开 Z-fighting
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

            // 1. 根据平面类型着色区分
            // 地面/桌面 (水平): 半透明荧光绿
            // 墙面/门面 (垂直): 半透明冷青蓝 (高对比度一目了然)
            val isVertical = (plane.type == Plane.Type.VERTICAL)
            if (isVertical) {
                // 垂直墙面填充: 半透明青蓝
                GLES20.glUniform4f(uColor, 0.0f, 0.65f, 1.0f, 0.28f)
            } else {
                // 水平地面填充: 半透明终端绿
                GLES20.glUniform4f(uColor, 0.0f, 0.90f, 0.40f, 0.22f)
            }

            // 三角扇 (Triangle Fan) 填充平面内部
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, pointCount)

            // 2. 勾勒边缘实线轮廓，方便看出边界
            GLES20.glLineWidth(4.0f)
            if (isVertical) {
                GLES20.glUniform4f(uColor, 0.2f, 0.85f, 1.0f, 0.90f) // 亮青边缘
            } else {
                GLES20.glUniform4f(uColor, 0.2f, 1.0f, 0.5f, 0.80f)  // 亮绿边缘
            }
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, pointCount)

            GLES20.glDisableVertexAttribArray(aPosition)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
