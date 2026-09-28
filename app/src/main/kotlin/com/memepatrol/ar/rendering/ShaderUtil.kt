package com.memepatrol.ar.rendering

import android.opengl.GLES20
import android.util.Log

object ShaderUtil {
    private const val TAG = "ShaderUtil"

    fun loadGLShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)

        if (compileStatus[0] == 0) {
            val errorMsg = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader: $errorMsg")
        }
        return shader
    }

    fun checkGLError(label: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL Error $label: $error")
        }
    }
}
