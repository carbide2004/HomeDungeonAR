package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 工业级 10cm x 10cm 物理网格地面渲染器：
 * 严格吸附在已检测到的地面多边形上，带有每隔 0.10m 的高对比度坐标网格线
 */
class TenCentimeterGridRenderer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    fun createOnGlThread() {
        val vs = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            varying vec2 v_LocalXZ;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                // 传递局部 X-Z 物理坐标给片段着色器计算 10cm 网格
                v_LocalXZ = a_Position.xz;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec2 v_LocalXZ;
            uniform vec4 u_Color;
            void main() {
                // 严格 10cm x 10cm 周期网格: 1 米有 10 个格子
                // 周期 = 0.10m => 系数 = 2.0 * PI / 0.10 = 62.831853
                vec2 grid = abs(sin(v_LocalXZ * 31.415926));
                
                // 线宽控制: 细网格线高亮
                float line = step(0.93, max(grid.x, grid.y));
                
                // 1 米大粗线: 每 10 个小格有一条主基准线 (3.1415926 * 2 = 6.283185)
                vec2 majorGrid = abs(sin(v_LocalXZ * 3.1415926));
                float majorLine = step(0.97, max(majorGrid.x, majorGrid.y));

                // 基础面半透明深墨绿，网格亮荧光绿，大基准线高亮青绿
                vec3 baseColor = u_Color.rgb * 0.4;
                vec3 lineColor = mix(vec3(0.0, 1.0, 0.45), vec3(0.3, 1.0, 0.9), majorLine);
                vec3 finalColor = mix(baseColor, lineColor, max(line, majorLine));

                float alpha = mix(0.15, 0.85, max(line, majorLine));
                gl_FragColor = vec4(finalColor, alpha * u_Color.a);
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

    fun drawFloorGrids(
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
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING ||
                plane.trackingState != TrackingState.TRACKING ||
                plane.subsumedBy != null
            ) {
                continue
            }

            val polygon = plane.polygon
            val pointCount = polygon.limit() / 2
            if (pointCount < 3) continue

            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)
            GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)

            val vertexArray = FloatArray(pointCount * 3)
            for (i in 0 until pointCount) {
                vertexArray[i * 3 + 0] = polygon.get(i * 2 + 0)
                vertexArray[i * 3 + 1] = 0.001f // 贴地浮起 1mm
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

            GLES20.glUniform4f(uColor, 0.0f, 0.95f, 0.45f, 0.85f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, pointCount)

            GLES20.glDisableVertexAttribArray(aPosition)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
