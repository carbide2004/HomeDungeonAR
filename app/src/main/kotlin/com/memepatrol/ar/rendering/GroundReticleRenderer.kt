package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 纯物理贴地圆环：顶点严格平铺在 X-Z 水平地面，法向量垂直向上 (0, 1, 0)
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
        // 构建地面水平圆形瞄准光环几何体 (半径 0.35m, 48 个平滑分段)
        // 关键：在水平地面局部空间中，X 为水平，Z 为进深，Y 严格为 0 (平铺在地面上)
        val segments = 48
        val radius = 0.35f
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Short>()

        // 顶点 0: 圆心 (X=0, Y=0, Z=0)
        vertices.add(0f); vertices.add(0.001f); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (i * 2.0 * Math.PI / segments).toFloat()
            val x = radius * kotlin.math.cos(angle)
            val z = radius * kotlin.math.sin(angle)
            // 顶点坐标: (x, 0.001f, z) 严格平铺在水平面上
            vertices.add(x)
            vertices.add(0.001f) // 上浮 1mm 避免地砖 Z-fighting
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
            varying vec2 v_GroundPos;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                // 将 X-Z 平面坐标传递给片段着色器计算同心圆环
                v_GroundPos = a_Position.xz;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec2 v_GroundPos;
            uniform vec4 u_Color;
            void main() {
                // 距离地面圆心的归一化物理半径 [0, 1]
                float dist = length(v_GroundPos) / 0.35;
                
                // 绘制双层同心细环 + 中心微标
                float outerRing = smoothstep(0.85, 0.90, dist) - smoothstep(0.98, 1.0, dist);
                float innerRing = smoothstep(0.40, 0.44, dist) - smoothstep(0.50, 0.54, dist);
                float centerDot = 1.0 - smoothstep(0.0, 0.06, dist);
                
                float alpha = clamp(outerRing * 0.9 + innerRing * 0.5 + centerDot * 0.8, 0.0, 1.0);
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
     * 在世界物理地面坐标 (gx, gy, gz) 处绘制绝对水平贴地的发光圆环
     */
    fun drawAtGroundPosition(
        gx: Float,
        gy: Float,
        gz: Float,
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

        // 核心：旋转为严格单位阵 (法向量永远严格垂直于物理重力 0, 1, 0)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, gx, gy, gz)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColor, 0.0f, 1.0f, 0.55f, 0.90f)

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
