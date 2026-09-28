package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 绝对零高度纯平地面标桩 (Zero-Height Flat Ground Pin)
 * 物理厚度严格为 0，像一张发光贴纸直接印在地砖表面上，彻底消除任何 10cm 悬空错觉
 */
class GroundPinRenderer {

    private var program = 0
    private var uMvpMatrix = 0
    private var uColor = 0
    private var aPosition = 0

    private val vertexBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer
    private val indexCount: Int

    init {
        // 构建严格平铺在 X-Z 平面的同心圆环地钉 (外径 7cm, 36个平滑分段, Y 严格为 0)
        val segments = 36
        val radius = 0.07f
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Short>()

        // 顶点 0: 圆心 (X=0, Y=0.0f, Z=0)
        vertices.add(0f); vertices.add(0.0005f); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (i * 2.0 * Math.PI / segments).toFloat()
            val x = radius * kotlin.math.cos(angle)
            val z = radius * kotlin.math.sin(angle)
            // 严格平铺在地面，Y 仅上浮 0.5mm 避免与地砖 Z-fighting
            vertices.add(x)
            vertices.add(0.0005f)
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
            varying vec2 v_LocalXZ;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_LocalXZ = a_Position.xz;
            }
        """.trimIndent()

        val fs = """
            precision mediump float;
            varying vec2 v_LocalXZ;
            uniform vec4 u_Color;
            void main() {
                float dist = length(v_LocalXZ) / 0.07;
                
                // 1. 外圈细环 (半径 0.85 ~ 0.98)
                float outerRing = smoothstep(0.85, 0.90, dist) - smoothstep(0.96, 1.0, dist);
                // 2. 内圈细环 (半径 0.40 ~ 0.50)
                float innerRing = smoothstep(0.40, 0.44, dist) - smoothstep(0.48, 0.52, dist);
                // 3. 中心十字微点 (半径 0.0 ~ 0.10)
                float centerDot = 1.0 - smoothstep(0.0, 0.12, dist);
                
                // 4. 十字准线
                float crossX = step(abs(v_LocalXZ.x), 0.0015) * step(dist, 0.95);
                float crossZ = step(abs(v_LocalXZ.y), 0.0015) * step(dist, 0.95);
                float crosshair = max(crossX, crossZ);

                float alpha = clamp(outerRing * 1.0 + innerRing * 0.7 + centerDot * 0.9 + crosshair * 0.85, 0.0, 1.0);
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

    fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray) {
        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(uColor, 0.0f, 1.0f, 0.55f, 0.95f)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
