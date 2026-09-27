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
import android.opengl.Matrix
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
import com.homedungeon.ar.audio.SpatialAudioEngine
import com.homedungeon.ar.audio.SoundTrackType
import com.homedungeon.ar.databinding.ActivityMainBinding
import com.homedungeon.ar.haptics.DetectorHapticDriver
import com.homedungeon.ar.rendering.BackgroundRenderer
import com.homedungeon.ar.rendering.CubeRenderer
import com.homedungeon.ar.rendering.PlaneVisualizer
import com.homedungeon.ar.rendering.WallDecalRenderer
import com.homedungeon.core.BlindCrawlerEntity
import com.homedungeon.ar.rendering.GroundReticleRenderer
import com.homedungeon.core.GroundPhysicsEngine
import com.homedungeon.core.EntityState
import com.homedungeon.core.DetectorMath
import com.homedungeon.core.SpatialAudioMath
import com.homedungeon.core.Vector3
import com.homedungeon.core.WallAnomalyStateMachine
import com.homedungeon.core.WallPoseDerivation
import com.google.ar.core.Pose
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
    private val groundReticleRenderer = GroundReticleRenderer()
    private val crawlerEntity = BlindCrawlerEntity()
    private lateinit var hapticDriver: DetectorHapticDriver
    private lateinit var audioEngine: SpatialAudioEngine

    private var currentK = 2.0f
    private var terminalFilterEnabled = true
    private var showDebugMarker = true
    private var virtualFloorY: Float = -1.35f // 默认离地基准高度 1.35m
    private var isFloorLocked = false
    @Volatile
    private var pendingCalibrateFloor = false
    private var currentGroundTargetPos: Vector3? = null
    private var viewportWidth = 1080
    private var viewportHeight = 2400
    private var lastFrameTimestamp = System.currentTimeMillis()

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
        hapticDriver = DetectorHapticDriver(this)
        audioEngine = SpatialAudioEngine(this)

        binding.btnK1.setOnClickListener { setK(1.0f) }
        binding.btnK2.setOnClickListener { setK(2.0f) }
        binding.btnK4.setOnClickListener { setK(4.0f) }

        binding.btnCalibrateFloor.setOnClickListener {
            pendingCalibrateFloor = true
        }

        binding.btnToggleDebugMarker.setOnClickListener {
            showDebugMarker = !showDebugMarker
            if (showDebugMarker) {
                binding.btnToggleDebugMarker.text = "调试标点: 开"
                binding.btnToggleDebugMarker.backgroundTintList = ContextCompat.getColorStateList(this, R.color.terminal_dark)
                binding.btnToggleDebugMarker.setTextColor(ContextCompat.getColor(this, R.color.terminal_green))
                Toast.makeText(this, "已开启实体碰撞标点 (可视)", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnToggleDebugMarker.text = "调试标点: 隐形"
                binding.btnToggleDebugMarker.backgroundTintList = ContextCompat.getColorStateList(this, R.color.terminal_green)
                binding.btnToggleDebugMarker.setTextColor(ContextCompat.getColor(this, R.color.black))
                Toast.makeText(this, "已开启完全隐形潜行模式", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRespawnEntity.setOnClickListener {
            crawlerEntity.reset()
            synchronized(anchors) {
                for (a in anchors) a.detach()
                anchors.clear()
            }
            runOnUiThread {
                binding.tvEntityState.text = "实体状态: 未初始化 (请重新轻触地面生成)"
                Toast.makeText(this, "异常实体已驱逐，请重新投放", Toast.LENGTH_SHORT).show()
            }
        }

        setupGlSurfaceView()
    }

    private fun setK(k: Float) {
        currentK = k
        binding.btnK1.backgroundTintList = ContextCompat.getColorStateList(this, if (k == 1.0f) R.color.terminal_green else R.color.terminal_dark)
        binding.btnK1.setTextColor(ContextCompat.getColor(this, if (k == 1.0f) R.color.black else R.color.terminal_green))

        binding.btnK2.backgroundTintList = ContextCompat.getColorStateList(this, if (k == 2.0f) R.color.terminal_green else R.color.terminal_dark)
        binding.btnK2.setTextColor(ContextCompat.getColor(this, if (k == 2.0f) R.color.black else R.color.terminal_green))

        binding.btnK4.backgroundTintList = ContextCompat.getColorStateList(this, if (k == 4.0f) R.color.terminal_green else R.color.terminal_dark)
        binding.btnK4.setTextColor(ContextCompat.getColor(this, if (k == 4.0f) R.color.black else R.color.terminal_green))
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
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
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
        groundReticleRenderer.createOnGlThread()
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(display?.rotation ?: 0, width, height)
        viewportWidth = width
        viewportHeight = height
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

        val camera = frame.camera
        val trackingState = camera.trackingState

        // Matrices
        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // 核心数学修正：从 viewMatrix 的逆矩阵直接提取相机在世界空间中的绝对真实位置与视线向量！
        // 在 OpenGL 中，viewMatrix 的第 3 列 (索引 2, 6, 10) 正是相机的视线后方 (+Z)，
        // 取反即为相机的严格正前方向量 (-viewMatrix[2], -viewMatrix[6], -viewMatrix[10])
        val invViewMatrix = FloatArray(16)
        Matrix.invertM(invViewMatrix, 0, viewMatrix, 0)

        val camPos = Vector3(invViewMatrix[12], invViewMatrix[13], invViewMatrix[14])
        // 视线前向单位向量：
        val camFwd = Vector3(-viewMatrix[2], -viewMatrix[6], -viewMatrix[10]).normalized()
        // 屏幕右侧单位向量：
        val camRight = Vector3(viewMatrix[0], viewMatrix[4], viewMatrix[8]).normalized()

        // 核心：利用物理重力反投影计算准星投向物理地表的绝对交点
        // 彻底丢弃单目 ARCore 不可靠的平面拟合，由重力先验保证绝对水平、绝不倾斜
        currentGroundTargetPos = GroundPhysicsEngine.projectReticleToAbsoluteFloor(
            cameraWorldPos = camPos,
            cameraForwardRay = camFwd,
            standingEyeHeight = if (isFloorLocked) (camPos.y - virtualFloorY) else 1.35f
        )

        // 按钮点击处理: 锁定当前准心投射到的物理地面高度
        if (pendingCalibrateFloor && trackingState == TrackingState.TRACKING) {
            pendingCalibrateFloor = false
            currentGroundTargetPos?.let { target ->
                virtualFloorY = target.y
                isFloorLocked = true
                runOnUiThread {
                    hapticDriver.triggerOneShotTap(binding.root)
                    Toast.makeText(this, "物理地面高度已锁定 (Y=${"%.2f".format(virtualFloorY)}m)", Toast.LENGTH_SHORT).show()
                    binding.btnCalibrateFloor.text = "地面: 已锁定"
                }
            }
        }

        // Update FPS
        frameCount++
        val now = System.currentTimeMillis()
        val delta = now - lastFpsTimestamp
        if (delta >= 1000) {
            currentFps = (frameCount * 1000.0 / delta).toInt()
            frameCount = 0
            lastFpsTimestamp = now
        }

        // Handle Tap for Hit Testing (在绝对物理地表光环处投放实体)
        val tap = queuedSingleTaps.poll()
        if (tap != null && trackingState == TrackingState.TRACKING) {
            val targetPos = currentGroundTargetPos
            if (targetPos != null) {
                crawlerEntity.spawnAt(targetPos)
                hapticDriver.triggerOneShotTap(binding.root)
            }
        }

        // 3. 驱动实体 AI 并在世界坐标系计算声震物理量
        var maxIntensity = 0.0f
        var targetDistance = -1.0f
        var targetCosTheta = -1.0f

        var spatialAudioResult = com.homedungeon.core.SpatialAudioResult(0f, 0f, -1f, 0f)

        val nowMs = System.currentTimeMillis()
        val deltaSeconds = (nowMs - lastFrameTimestamp).coerceIn(1L, 200L) / 1000.0f
        lastFrameTimestamp = nowMs

        if (crawlerEntity.state != EntityState.IDLE) {
            val entityPos = crawlerEntity.position
            val (intensity, dist, cos) = DetectorMath.calculateIntensity(
                camPos, camFwd, entityPos, k = currentK
            )
            maxIntensity = intensity
            targetDistance = dist
            targetCosTheta = cos

            // 实体 AI 更新: 如果玩家准星对准实体 (cos >= 0.88 且 dist <= 3.5m)，实体触发 ALERT 警觉并停步
            val isGazed = (cos >= 0.88f && dist in 0.2f..3.5f)
            crawlerEntity.update(deltaSeconds, isGazed)

            // 计算该实体的立体声像
            spatialAudioResult = SpatialAudioMath.calculateSpatialGain(
                camPos, camFwd, camRight, crawlerEntity.position
            )
        }

        // 1. Draw camera feed background with Terminal Vision Shader
        backgroundRenderer.draw(
            frame = frame,
            intensity = maxIntensity,
            filterEnabled = terminalFilterEnabled
        )

        // 2. 渲染严格水平的物理贴地光环 (彻底消除 ARCore 原始网格与上翘下斜)
        if (trackingState == TrackingState.TRACKING) {
            currentGroundTargetPos?.let { target ->
                groundReticleRenderer.drawAtGroundPosition(
                    gx = target.x,
                    gy = target.y,
                    gz = target.z,
                    viewMatrix = viewMatrix,
                    projMatrix = projMatrix
                )
            }
        }

        // 3. 渲染实体调试位置标点 (仅在开启 showDebugMarker 且实体激活时绘制紧凑红色微小线框，验证其实体走位)
        if (trackingState == TrackingState.TRACKING && crawlerEntity.state != EntityState.IDLE && showDebugMarker) {
            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            val ePos = crawlerEntity.position
            Matrix.translateM(modelMatrix, 0, ePos.x, ePos.y, ePos.z)
            cubeRenderer.draw(modelMatrix, viewMatrix, projMatrix)
        }

        // Update Spatial Audio Engine
        if (trackingState == TrackingState.TRACKING && targetDistance >= 0f) {
            audioEngine.updateSpatialGain(spatialAudioResult.leftVolume, spatialAudioResult.rightVolume)
        } else {
            audioEngine.updateSpatialGain(0f, 0f)
        }

        // 4. Update Diagnostics UI & Haptics on main thread
        runOnUiThread {
            // Trigger dynamic haptic feedback on UI thread via View pipeline
            if (trackingState == TrackingState.TRACKING) {
                hapticDriver.update(maxIntensity, binding.root)
            }

            if (trackingState == TrackingState.TRACKING) {
                val stagePrompt = when (crawlerEntity.state) {
                    EntityState.IDLE -> "准心对准地面光环，轻触屏幕投放 [SCP 盲爪]"
                    EntityState.PATROL -> "实体正在地面无声潜伏游走... 戴上耳机盲扫探测"
                    EntityState.ALERT -> "【警戒】准心已压制目标！它已停止移动"
                }
                binding.tvStatus.text = stagePrompt

                val stateStr = when (crawlerEntity.state) {
                    EntityState.IDLE -> "未投放"
                    EntityState.PATROL -> "巡游潜伏中 (v ≈ 0.28m/s)"
                    EntityState.ALERT -> "已受电磁压制 (警觉定身)"
                }
                binding.tvEntityState.text = "实体状态: $stateStr"
            } else if (trackingState == TrackingState.PAUSED) {
                binding.tvStatus.text = "正在校准空间基准... (请缓慢平移手机扫描环境)"
            } else {
                binding.tvStatus.text = "追踪丢失 (尝试面向光线充足区域)"
            }

            val floorStatus = if (isFloorLocked) "物理锁定(Y=${"%.2f".format(virtualFloorY)}m)" else "自适应估计(H=1.35m)"
            binding.tvInfo.text = "重力地平: $floorStatus | 帧率: $currentFps FPS"

            if (crawlerEntity.state != EntityState.IDLE && targetDistance >= 0f) {
                val alignPct = (targetCosTheta.coerceAtLeast(0f) * 100).toInt()
                binding.tvDetectorHaptics.text = String.format(
                    "探测: 距离 %.2fm | 锁定率 %d%% | 奇术通量 I = %.2f (k=%.0f)",
                    targetDistance, alignPct, maxIntensity, currentK
                )

                val dirDesc = when {
                    spatialAudioResult.azimuthDegrees > 30f -> "右偏 %.0f°".format(spatialAudioResult.azimuthDegrees)
                    spatialAudioResult.azimuthDegrees < -30f -> "左偏 %.0f°".format(-spatialAudioResult.azimuthDegrees)
                    else -> "正前方"
                }

                binding.tvSpatialAudio.text = String.format(
                    "声源: [%s] | 方位: %s | 声道: L %.2f | R %.2f",
                    audioEngine.currentTrack.displayName, dirDesc,
                    spatialAudioResult.leftVolume, spatialAudioResult.rightVolume
                )
            } else {
                binding.tvDetectorHaptics.text = "探测: 未投放异常实体 (点击绿网地面投放)"
                binding.tvSpatialAudio.text = "声源: [盲爪爬行] | 待激活"
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
