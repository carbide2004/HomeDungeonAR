package com.homedungeon.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.homedungeon.ar.databinding.ActivityMainBinding
import com.homedungeon.ar.rendering.BackgroundRenderer
import com.homedungeon.ar.rendering.CubeRenderer
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ARMainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var session: Session? = null
    private var installRequested = false

    private val backgroundRenderer = BackgroundRenderer()
    private val cubeRenderer = CubeRenderer()

    private val anchors = ArrayList<Anchor>()
    private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(16)

    // FPS & Diagnostics
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            binding.permissionLayout.visibility = View.GONE
            setupArSession()
        } else {
            binding.permissionLayout.visibility = View.VISIBLE
            Toast.makeText(this, "需要相机权限以进行空间追踪", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantPermission.setOnClickListener {
            requestCameraPermission()
        }

        binding.btnClearAnchors.setOnClickListener {
            synchronized(anchors) {
                for (anchor in anchors) {
                    anchor.detach()
                }
                anchors.clear()
            }
            runOnUiThread {
                binding.tvInfo.text = "平面: -- | 锚点: 0 | 帧率: $currentFps FPS"
                Toast.makeText(this, "所有空间锚点已清除", Toast.LENGTH_SHORT).show()
            }
        }

        setupGlSurfaceView()
    }

    private fun setupGlSurfaceView() {
        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        binding.surfaceView.setWillNotDraw(false)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                queuedSingleTaps.offer(e)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        binding.surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }

        setupArSession()
    }

    private fun setupArSession() {
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                session = Session(this).apply {
                    val config = Config(this).apply {
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    }
                    configure(config)
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "请安装 Google Play Services for AR"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "ARCore 服务版本过旧，请升级"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "应用 SDK 版本过旧"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "当前设备不支持 ARCore 空间追踪"
                exception = e
            } catch (e: Exception) {
                message = "创建 AR 会话失败: ${e.message}"
                exception = e
            }

            if (message != null) {
                Log.e(TAG, "ARCore Session Error", exception)
                binding.tvStatus.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            binding.tvStatus.text = "相机已被占用，请重启应用"
            session = null
            return
        }

        binding.surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            binding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    // --- GLSurfaceView.Renderer implementation ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread()
        cubeRenderer.createOnGlThread()
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(display?.rotation ?: 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        // Associate texture if needed
        currentSession.setCameraTextureName(backgroundRenderer.textureId)

        val frame: Frame
        try {
            frame = currentSession.update()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception updating AR frame", t)
            return
        }

        // 1. Draw camera feed background
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        val trackingState = camera.trackingState

        // Update FPS
        frameCount++
        val now = System.currentTimeMillis()
        val delta = now - lastFpsTimestamp
        if (delta >= 1000) {
            currentFps = (frameCount * 1000.0 / delta).toInt()
            frameCount = 0
            lastFpsTimestamp = now
        }

        // Handle Tap for Hit Testing (Anchor Placement)
        val tap = queuedSingleTaps.poll()
        if (tap != null && trackingState == TrackingState.TRACKING) {
            val hitResults = frame.hitTest(tap.x, tap.y)
            for (hit in hitResults) {
                val trackable = hit.trackable
                val isValidHit = when (trackable) {
                    is Plane -> trackable.isPoseInPolygon(hit.hitPose) || trackable.isPoseInExtents(hit.hitPose)
                    is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    is InstantPlacementPoint -> true
                    else -> false
                }

                if (isValidHit) {
                    val newAnchor = hit.createAnchor()
                    synchronized(anchors) {
                        anchors.add(newAnchor)
                    }
                    triggerHapticFeedback()
                    break
                }
            }
        }

        // Matrices
        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // 2. Render Placed 3D Anchors
        if (trackingState == TrackingState.TRACKING) {
            synchronized(anchors) {
                val anchorIterator = anchors.iterator()
                while (anchorIterator.hasNext()) {
                    val anchor = anchorIterator.next()
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        val modelMatrix = FloatArray(16)
                        anchor.pose.toMatrix(modelMatrix, 0)
                        cubeRenderer.draw(modelMatrix, viewMatrix, projMatrix)
                    } else if (anchor.trackingState == TrackingState.STOPPED) {
                        anchorIterator.remove()
                    }
                }
            }
        }

        // 3. Update Diagnostics UI on main thread
        val allPlanes = currentSession.getAllTrackables(Plane::class.java)
        val floorCount = allPlanes.count { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
        val wallCount = allPlanes.count { it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING }
        val anchorCount = synchronized(anchors) { anchors.size }
        val camPose = camera.pose

        runOnUiThread {
            when (trackingState) {
                TrackingState.TRACKING -> {
                    binding.tvStatus.text = "空间基准已锁定 (支持地面/桌面/墙面锚定)"
                }
                TrackingState.PAUSED -> {
                    binding.tvStatus.text = "正在校准空间基准... (请缓慢平移手机扫描环境)"
                }
                TrackingState.STOPPED -> {
                    binding.tvStatus.text = "追踪丢失 (尝试面向光线充足区域)"
                }
            }

            binding.tvPose.text = String.format(
                "坐标: X: %+.2fm | Y: %+.2fm | Z: %+.2fm",
                camPose.tx(), camPose.ty(), camPose.tz()
            )
            binding.tvInfo.text = "地面: $floorCount | 墙面: $wallCount | 锚点: $anchorCount | 帧率: $currentFps FPS"
        }
    }

    private fun triggerHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(40)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback skipped: ${e.message}")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
