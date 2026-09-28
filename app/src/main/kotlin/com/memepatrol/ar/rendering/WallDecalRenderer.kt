package com.memepatrol.ar.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class WallDecalRenderer {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvpMatrix = 0
    private var uTextureBase = 0
    private var uTextureRevealed = 0
    private var uAlpha = 0
    private var uRevealFactor = 0

    private var textureBaseId = -1
    private var textureRevealedId = -1

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer

    init {
        // 墙纸物理尺寸: 宽 0.85m, 高 1.15m
        // 在贴图自身的局部坐标系中: 严格为立着的垂直平面 (X 沿水平, Y 沿垂直方向, Z=0)
        val w = 0.425f
        val h = 0.575f

        val vertices = floatArrayOf(
            -w, -h, 0.0f, // 0: 左下 (uv: 0, 1)
             w, -h, 0.0f, // 1: 右下 (uv: 1, 1)
             w,  h, 0.0f, // 2: 右上 (uv: 1, 0)
            -w,  h, 0.0f  // 3: 左上 (uv: 0, 0)
        )

        val texCoords = floatArrayOf(
            0f, 1f,
            1f, 1f,
            1f, 0f,
            0f, 0f
        )

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords)
                position(0)
            }

        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(indices)
                position(0)
            }
    }

    fun createOnGlThread() {
        val bmpBase = createWallpaperBitmap(isRevealed = false)
        val bmpRevealed = createWallpaperBitmap(isRevealed = true)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        textureBaseId = textures[0]
        textureRevealedId = textures[1]

        loadBitmapToTexture(textureBaseId, bmpBase)
        loadBitmapToTexture(textureRevealedId, bmpRevealed)

        bmpBase.recycle()
        bmpRevealed.recycle()

        val vertexShader = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform sampler2D u_TextureBase;
            uniform sampler2D u_TextureRevealed;
            uniform float u_Alpha;
            uniform float u_RevealFactor;

            void main() {
                vec4 cBase = texture2D(u_TextureBase, v_TexCoord);
                vec4 cRevealed = texture2D(u_TextureRevealed, v_TexCoord);
                vec4 col = mix(cBase, cRevealed, clamp(u_RevealFactor, 0.0, 1.0));
                gl_FragColor = vec4(col.rgb, col.a * u_Alpha);
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
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTextureBase = GLES20.glGetUniformLocation(program, "u_TextureBase")
        uTextureRevealed = GLES20.glGetUniformLocation(program, "u_TextureRevealed")
        uAlpha = GLES20.glGetUniformLocation(program, "u_Alpha")
        uRevealFactor = GLES20.glGetUniformLocation(program, "u_RevealFactor")
    }

    fun draw(
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        alpha: Float,
        revealFactor: Float
    ) {
        if (alpha <= 0.01f) {
            return
        }

        val mvMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform1f(uRevealFactor, revealFactor)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureBaseId)
        GLES20.glUniform1i(uTextureBase, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureRevealedId)
        GLES20.glUniform1i(uTextureRevealed, 1)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPosition)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        indexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadBitmapToTexture(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun createWallpaperBitmap(isRevealed: Boolean): Bitmap {
        val width = 512
        val height = 768
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. 泛黄斑驳旧墙纸底色
        canvas.drawColor(Color.argb(235, 78, 70, 42))

        val paint = Paint().apply {
            isAntiAlias = true
        }

        // 2. 绘制枯萎藤蔓纹理
        paint.color = Color.argb(170, 44, 42, 22)
        paint.strokeWidth = 6f
        paint.style = Paint.Style.STROKE

        for (i in 0 until 6) {
            val startY = 80f + i * 110f
            val path = Path().apply {
                moveTo(30f, startY)
                cubicTo(180f, startY - 40f, 320f, startY + 50f, 480f, startY - 20f)
                cubicTo(360f, startY + 60f, 220f, startY + 20f, 50f, startY + 70f)
            }
            canvas.drawPath(path, paint)
        }

        // 3. 绘制 6 个人脸轮廓
        val facePositions = listOf(
            Pair(140f, 180f), Pair(360f, 190f),
            Pair(160f, 380f), Pair(340f, 400f),
            Pair(150f, 580f), Pair(370f, 600f)
        )

        paint.style = Paint.Style.FILL

        for ((fx, fy) in facePositions) {
            if (!isRevealed) {
                // 阶段 1: 侧脸与微弱阴影轮廓
                paint.color = Color.argb(120, 32, 30, 16)
                canvas.drawOval(fx - 32f, fy - 45f, fx + 28f, fy + 45f, paint)
                paint.color = Color.argb(150, 22, 20, 12)
                canvas.drawCircle(fx + 18f, fy - 5f, 12f, paint)
            } else {
                // 阶段 3: 人脸骤然全部转正！空洞漆黑眼眶直视屏幕
                paint.color = Color.argb(220, 32, 30, 18)
                canvas.drawOval(fx - 38f, fy - 48f, fx + 38f, fy + 48f, paint)

                // 两个空洞眼眶
                paint.color = Color.argb(255, 8, 8, 4)
                canvas.drawCircle(fx - 14f, fy - 10f, 7f, paint)
                canvas.drawCircle(fx + 14f, fy - 10f, 7f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawArc(fx - 12f, fy + 12f, fx + 12f, fy + 26f, 0f, 180f, false, paint)
                paint.style = Paint.Style.FILL
            }
        }

        // 4. 阶段 3 浮现四个暗红大字：“到墙里来”
        if (isRevealed) {
            paint.color = Color.argb(235, 185, 40, 25)
            paint.textSize = 58f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("到 墙 里 来", width / 2f, height - 70f, paint)
        }

        return bitmap
    }
}
