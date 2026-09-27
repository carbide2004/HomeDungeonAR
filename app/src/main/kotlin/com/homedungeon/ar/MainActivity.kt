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
import com.homedungeon.ar.audio.SpatialAudioEngine
import com.homedungeon.ar.audio.SoundTrackType
import com.homedungeon.ar.databinding.ActivityMainBinding
import com.homedungeon.ar.haptics.DetectorHapticDriver
import com.homedungeon.ar.rendering.BackgroundRenderer
import com.homedungeon.ar.rendering.CubeRenderer
import com.homedungeon.ar.rendering.PlaneVisualizer
import com.homedungeon.ar.rendering.WallDecalRenderer
import com.homedungeon.core.AnomalyStage
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
    private val wallDecalRenderer = WallDecalRenderer()
    private val planeVisualizer = PlaneVisualizer()
    private val anomalyStateMachine = WallAnomalyStateMachine()
    private lateinit var hapticDriver: DetectorHapticDriver
    private lateinit var audioEngine: SpatialAudioEngine

    private var currentK = 2.0f
    private var terminalFilterEnabled = true
    private var viewportWidth = 1080
    private var viewportHeight = 2400
    private var lastFrameTimestamp = System.currentTimeMillis()
    private var hasTriggeredRevealHaptic = false

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

        binding.btnToggleFilter.setOnClickListener {
            terminalFilterEnabled = !terminalFilterEnabled
            if (terminalFilterEnabled) {
                binding.btnToggleFilter.text = "滤镜: 里侧终端"
                binding.btnToggleFilter.backgroundTintList = ContextCompat.getColorStateList(this, R.color.terminal_green)
                binding.btnToggleFilter.setTextColor(ContextCompat.getColor(this, R.color.black))
                Toast.makeText(this, "终端里侧滤镜已激活", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnToggleFilter.text = "滤镜: 原始直通"
                binding.btnToggleFilter.backgroundTintList = ContextCompat.getColorStateList(this, R.color.terminal_dark)
                binding.btnToggleFilter.setTextColor(ContextCompat.getColor(this, R.color.terminal_green))
                Toast.makeText(this, "已切换为现实相机直通模式", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleAudio.setOnClickListener {
            val newTrack = audioEngine.toggleTrack()
            binding.btnToggleAudio.text = "🔊 ${newTrack.displayName}"
            Toast.makeText(this, "声源切换为: ${newTrack.displayName}", Toast.LENGTH_SHORT).show()
        }

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
            anomalyStateMachine.reset()
            hasTriggeredRevealHaptic = false
            runOnUiThread {
                binding.tvInfo.text = "平面: -- | 锚点: 0 | 帧率: $currentFps FPS"
                Toast.makeText(this, "所有空间锚点已清除", Toast.LENGTH_SHORT).show()
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
        wallDecalRenderer.createOnGlThread()
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

        // Update FPS
        frameCount++
        val now = System.currentTimeMillis()
        val delta = now - lastFpsTimestamp
        if (delta >= 1000) {
            currentFps = (frameCount * 1000.0 / delta).toInt()
            frameCount = 0
            lastFpsTimestamp = now
        }

        // Handle Tap for Hit Testing (基于地面反推垂直墙面)
        val tap = queuedSingleTaps.poll()
        if (tap != null && trackingState == TrackingState.TRACKING) {
            val allPlanes = currentSession.getAllTrackables(Plane::class.java)
            val floorPlane = allPlanes.firstOrNull {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING
            }

            val dispPose = camera.displayOrientedPose
            val camPos = Vector3(dispPose.tx(), dispPose.ty(), dispPose.tz())
            val zAxis = dispPose.zAxis
            val camFwd = Vector3(-zAxis[0], -zAxis[1], -zAxis[2])

            var createdAnchor: Anchor? = null

            // 优先方案: 基于已锁定的物理地面方程，通过视线直接构造绝对垂直的墙面位姿
            if (floorPlane != null) {
                val floorY = floorPlane.centerPose.ty()
                val derived = WallPoseDerivation.deriveFromFloorIntersection(
                    floorY = floorY,
                    camPos = camPos,
                    camForwardRay = camFwd,
                    targetHeightAboveFloor = 1.15f
                )

                if (derived != null) {
                    val halfAngleRad = (derived.yawAngleDegrees * 0.5f) * (Math.PI.toFloat() / 180f)
                    val qy = kotlin.math.sin(halfAngleRad)
                    val qw = kotlin.math.cos(halfAngleRad)
                    val wallPose = Pose.makeTranslation(derived.position.x, derived.position.y, derived.position.z)
                        .compose(Pose.makeRotation(0f, qy, 0f, qw))

                    createdAnchor = currentSession.createAnchor(wallPose)
                }
            }

            // 降级后备: 若暂未识别出地面，则回退到常规平面点击
            if (createdAnchor == null) {
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
                        createdAnchor = hit.createAnchor()
                        break
                    }
                }
            }

            if (createdAnchor != null) {
                synchronized(anchors) {
                    anchors.add(createdAnchor)
                }
                anomalyStateMachine.onAnchorPlaced()
                hasTriggeredRevealHaptic = false
                runOnUiThread {
                    hapticDriver.triggerOneShotTap(binding.root)
                }
            }
        }

        // 3. Calculate Haptic Detector & Spatial Audio feedback based on nearest anchor
        var maxIntensity = 0.0f
        var nearestDistance = -1.0f
        var nearestCosTheta = -1.0f

        val dispPose = camera.displayOrientedPose
        val camPos = Vector3(dispPose.tx(), dispPose.ty(), dispPose.tz())
        val zAxis = dispPose.zAxis
        val camFwd = Vector3(-zAxis[0], -zAxis[1], -zAxis[2])
        val xAxis = dispPose.xAxis
        val camRight = Vector3(xAxis[0], xAxis[1], xAxis[2])

        var spatialAudioResult = com.homedungeon.core.SpatialAudioResult(0f, 0f, -1f, 0f)

        // 状态机更新时间步长
        val nowMs = System.currentTimeMillis()
        val deltaSeconds = (nowMs - lastFrameTimestamp).coerceIn(1L, 200L) / 1000.0f
        lastFrameTimestamp = nowMs

        synchronized(anchors) {
            for (anchor in anchors) {
                if (anchor.trackingState != TrackingState.STOPPED) {
                    val anchorPose = anchor.pose
                    val targetPos = Vector3(anchorPose.tx(), anchorPose.ty(), anchorPose.tz())
                    val (intensity, dist, cos) = DetectorMath.calculateIntensity(
                        camPos, camFwd, targetPos, k = currentK
                    )
                    if (intensity > maxIntensity || nearestDistance < 0f) {
                        maxIntensity = intensity
                        nearestDistance = dist
                        nearestCosTheta = cos

                        // 判断是否正对目标墙面 (视场角 30° 锥体内, cos >= 0.866)
                        val isLookingAtWall = (cos >= 0.866f && dist > 0f)
                        anomalyStateMachine.update(isLookingAtWall, deltaSeconds)

                        // 阶段 2 (转开视线): 声音在耳机里向右后方墙根爬行位移，诱导并恐吓玩家！
                        val audioTargetPos = if (anomalyStateMachine.currentStage == AnomalyStage.PHASE2_LOOKING_AWAY) {
                            val crawlProgress = (anomalyStateMachine.lookAwayDuration / 2.0f).coerceIn(0f, 1f)
                            targetPos - (camRight * (crawlProgress * 2.2f))
                        } else {
                            targetPos
                        }

                        // 阶段 3 (转回头直视反转): 触发一次惊吓性强顿挫触觉脉冲
                        if (anomalyStateMachine.currentStage == AnomalyStage.PHASE3_FACE_REVEAL && !hasTriggeredRevealHaptic) {
                            hasTriggeredRevealHaptic = true
                            runOnUiThread {
                                hapticDriver.triggerOneShotTap(binding.root)
                            }
                        }

                        spatialAudioResult = SpatialAudioMath.calculateSpatialGain(
                            camPos, camFwd, camRight, audioTargetPos
                        )
                    }
                }
            }
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

        // 2. 渲染已识别的空间平面多边形 (地面绿色、墙面青蓝色)
        if (trackingState == TrackingState.TRACKING) {
            val allPlanes = currentSession.getAllTrackables(Plane::class.java)
            planeVisualizer.drawPlanes(allPlanes, viewMatrix, projMatrix)
        }

        // 3. Render Placed 3D Anchors & Wall Decals
        if (trackingState == TrackingState.TRACKING) {
            synchronized(anchors) {
                val anchorIterator = anchors.iterator()
                while (anchorIterator.hasNext()) {
                    val anchor = anchorIterator.next()
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        val modelMatrix = FloatArray(16)
                        anchor.pose.toMatrix(modelMatrix, 0)

                        // 绘制贴墙怪谈黄色墙纸 (根据状态机控制淡入度与人脸异变因子)
                        wallDecalRenderer.draw(
                            modelMatrix = modelMatrix,
                            viewMatrix = viewMatrix,
                            projMatrix = projMatrix,
                            alpha = anomalyStateMachine.surfaceAlpha,
                            revealFactor = anomalyStateMachine.revealFactor
                        )

                        // 仅在墙纸未完全显现时保留线框基准
                        if (anomalyStateMachine.surfaceAlpha < 0.9f) {
                            cubeRenderer.draw(modelMatrix, viewMatrix, projMatrix)
                        }
                    } else if (anchor.trackingState == TrackingState.STOPPED) {
                        anchorIterator.remove()
                    }
                }
            }
        }

        // Update Spatial Audio Engine
        if (trackingState == TrackingState.TRACKING && nearestDistance >= 0f) {
            audioEngine.updateSpatialGain(spatialAudioResult.leftVolume, spatialAudioResult.rightVolume)
        } else {
            audioEngine.updateSpatialGain(0f, 0f)
        }

        // 4. Update Diagnostics UI & Haptics on main thread
        val allPlanes = currentSession.getAllTrackables(Plane::class.java)
        val floorCount = allPlanes.count { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING }
        val wallCount = allPlanes.count { it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING }
        val anchorCount = synchronized(anchors) { anchors.size }

        runOnUiThread {
            // Trigger dynamic haptic feedback on UI thread via View pipeline
            if (trackingState == TrackingState.TRACKING) {
                hapticDriver.update(maxIntensity, binding.root)
            }

            if (trackingState == TrackingState.TRACKING) {
                val stagePrompt = when (anomalyStateMachine.currentStage) {
                    AnomalyStage.IDLE -> if (floorCount > 0) "准心对准墙根线点击：立起 [S4 垂直墙面]" else "正在锁定地面... (平移手机扫描地面)"
                    AnomalyStage.CALIBRATED_SEARCHING -> "已标定：请将镜头对准该墙面开始观测..."
                    AnomalyStage.PHASE1_VINES_FADING_IN -> "墙纸正在渗出... 请保持注视"
                    AnomalyStage.PHASE1_STABLE -> "听！墙根异响正移向右后方... 转开视线寻找声源"
                    AnomalyStage.PHASE2_LOOKING_AWAY -> "声音已移至你背后... (墙体正在暗中畸变)"
                    AnomalyStage.PHASE3_FACE_REVEAL -> "【高危】它们全部转过脸来了！“到墙里来”！"
                }
                binding.tvStatus.text = stagePrompt
            } else if (trackingState == TrackingState.PAUSED) {
                binding.tvStatus.text = "正在校准空间基准... (请缓慢平移手机扫描环境)"
            } else {
                binding.tvStatus.text = "追踪丢失 (尝试面向光线充足区域)"
            }

            binding.tvPose.text = String.format(
                "坐标: X: %+.2fm | Y: %+.2fm | Z: %+.2fm",
                dispPose.tx(), dispPose.ty(), dispPose.tz()
            )
            binding.tvInfo.text = "已锁定地面: $floorCount | 锚点: $anchorCount | 帧率: $currentFps FPS"

            if (anchorCount > 0 && nearestDistance >= 0f) {
                val alignPct = (nearestCosTheta.coerceAtLeast(0f) * 100).toInt()
                binding.tvDetectorHaptics.text = String.format(
                    "探测: 距离 %.2fm | 对准 %d%% | 强度 I = %.2f (k=%.0f)",
                    nearestDistance, alignPct, maxIntensity, currentK
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
                binding.tvDetectorHaptics.text = "探测: 未部署异常源 (点击空间部署锚点)"
                binding.tvSpatialAudio.text = "声源: [待命中] (点击墙面/地面部署发声锚点)"
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
