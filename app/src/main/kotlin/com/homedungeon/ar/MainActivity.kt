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
import com.homedungeon.core.GroundPlaneFilter
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
    private val planeVisualizer = PlaneVisualizer()
    private val crawlerEntity = BlindCrawlerEntity()
    private lateinit var hapticDriver: DetectorHapticDriver
    private lateinit var audioEngine: SpatialAudioEngine

    private var currentK = 2.0f
    private var terminalFilterEnabled = true
    private var showDebugMarker = true
    private var calibratedFloorY: Float? = null
    @Volatile
    private var pendingCalibrateFloor = false
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
            // 标记在下一次 GL 渲染线程的 update() 帧中安全获取物理位姿，杜绝 MissingGlContextException
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
        planeVisualizer.createOnGlThread()
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

        // 安全处理准心锁定地面零点 (从屏幕中心向视线内的可见地面发射射线精确求交)
        if (pendingCalibrateFloor && trackingState == TrackingState.TRACKING) {
            pendingCalibrateFloor = false
            val centerX = viewportWidth / 2f
            val centerY = viewportHeight / 2f
            val hitResults = frame.hitTest(centerX, centerY)

            var lockedY: Float? = null
            for (hit in hitResults) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                    lockedY = hit.hitPose.ty()
                    break
                }
            }

            if (lockedY != null) {
                calibratedFloorY = lockedY
                runOnUiThread {
                    hapticDriver.triggerOneShotTap(binding.root)
                    Toast.makeText(this, "地面零点已从准心位置锁定: ${"%.2f".format(lockedY)}m", Toast.LENGTH_SHORT).show()
                    binding.btnCalibrateFloor.text = "零点: ${"%.2f".format(lockedY)}m"
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "未对准地面！请将屏幕中心准星对准地面后再点", Toast.LENGTH_SHORT).show()
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

        // 获取并严苛筛选面积最大、最稳的唯一主地面
        val allTrackables = currentSession.getAllTrackables(Plane::class.java)
        val validFloorPlanes = allTrackables.filter { plane ->
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.trackingState == TrackingState.TRACKING &&
                    plane.subsumedBy == null &&
                    GroundPlaneFilter.isStrictlyHorizontal(
                        plane.centerPose.yAxis[0],
                        plane.centerPose.yAxis[1],
                        plane.centerPose.yAxis[2]
                    )
        }

        // 按面积最大降序排序，只取最大单一块主地面
        val primaryFloorPlane = validFloorPlanes.maxByOrNull {
            GroundPlaneFilter.calculateExtentArea(it.extentX, it.extentZ)
        }

        // Handle Tap for Hit Testing (在地面投放实体生成点)
        val tap = queuedSingleTaps.poll()
        if (tap != null && trackingState == TrackingState.TRACKING) {
            val hitResults = frame.hitTest(tap.x, tap.y)
            for (hit in hitResults) {
                val trackable = hit.trackable
                if (trackable == primaryFloorPlane || (primaryFloorPlane == null && trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING)) {
                    val hitPose = hit.hitPose
                    val groundY = calibratedFloorY ?: hitPose.ty()
                    val groundPos = Vector3(hitPose.tx(), groundY, hitPose.tz())
                    
                    crawlerEntity.spawnAt(groundPos)
                    hapticDriver.triggerOneShotTap(binding.root)
                    break
                }
            }
        }

        // 3. 驱动实体 AI 并在世界坐标系计算声震物理量
        var maxIntensity = 0.0f
        var targetDistance = -1.0f
        var targetCosTheta = -1.0f

        val dispPose = camera.displayOrientedPose
        val camPos = Vector3(dispPose.tx(), dispPose.ty(), dispPose.tz())
        val zAxis = dispPose.zAxis
        val camFwd = Vector3(-zAxis[0], -zAxis[1], -zAxis[2])
        val xAxis = dispPose.xAxis
        val camRight = Vector3(xAxis[0], xAxis[1], xAxis[2])

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

        // Matrices
        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // 2. 渲染经过严苛过滤的唯一主地面 (官方标准 PlaneRenderer 网格羽化)
        if (trackingState == TrackingState.TRACKING) {
            planeVisualizer.drawMainFloor(primaryFloorPlane, viewMatrix, projMatrix)

            // 行业标准：屏幕中心对准物理地面时，在地面渲染贴地锁定光圈 (Ground Reticle)
            val centerX = viewportWidth / 2f
            val centerY = viewportHeight / 2f
            val hitResults = frame.hitTest(centerX, centerY)
            for (hit in hitResults) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
                    val reticleMatrix = FloatArray(16)
                    hit.hitPose.toMatrix(reticleMatrix, 0)
                    planeVisualizer.drawGroundReticle(reticleMatrix, viewMatrix, projMatrix)
                    break
                }
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
        val floorArea = if (primaryFloorPlane != null) {
            GroundPlaneFilter.calculateExtentArea(primaryFloorPlane.extentX, primaryFloorPlane.extentZ)
        } else 0f

        runOnUiThread {
            // Trigger dynamic haptic feedback on UI thread via View pipeline
            if (trackingState == TrackingState.TRACKING) {
                hapticDriver.update(maxIntensity, binding.root)
            }

            if (trackingState == TrackingState.TRACKING) {
                val stagePrompt = when (crawlerEntity.state) {
                    EntityState.IDLE -> {
                        if (calibratedFloorY == null) {
                            "【步骤 1/2】准心对准可见地面，点击「🎯 锁定地面零点」"
                        } else if (primaryFloorPlane != null) {
                            "【步骤 2/2】基准已锁定！点击绿网地面投放 [SCP 盲爪]"
                        } else {
                            "基准已校准，正在锁定地面... (平移手机扫描地面)"
                        }
                    }
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

            binding.tvPose.text = String.format(
                "坐标: X: %+.2fm | Y: %+.2fm | Z: %+.2fm",
                dispPose.tx(), dispPose.ty(), dispPose.tz()
            )

            val calibText = if (calibratedFloorY != null) "已锁定(Y=${"%.2f".format(calibratedFloorY)}m)" else "未锁定"
            binding.tvInfo.text = "主地面: ${"%.1f".format(floorArea)}㎡ ($calibText) | 帧率: $currentFps FPS"

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
