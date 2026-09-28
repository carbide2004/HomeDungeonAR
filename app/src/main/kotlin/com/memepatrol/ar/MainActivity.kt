package com.memepatrol.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.memepatrol.ar.databinding.ActivityMainBinding
import com.memepatrol.ar.haptics.DetectorHapticDriver
import com.memepatrol.ar.rendering.BackgroundRenderer
import com.memepatrol.ar.rendering.GroundPinRenderer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MemePatrolMain"
    }

    private lateinit var binding: ActivityMainBinding
    private var session: Session? = null
    private var installRequested = false

    private val backgroundRenderer = BackgroundRenderer()
    private val groundPinRenderer = GroundPinRenderer()
    private lateinit var hapticDriver: DetectorHapticDriver

    // 严谨存储所有打下的物理地面刚性标桩
    private val groundPins = CopyOnWriteArrayList<Anchor>()

    // 地面高度全局补偿偏移 (米)：解决单目 ARCore 平面悬空或下陷问题
    private var floorOffsetMeters: Float = 0.0f

    @Volatile
    private var pendingAddPin = false

    private var viewportWidth = 1080
    private var viewportHeight = 2400

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

        // 核心交互：准星对准地面，点击按钮精准打桩
        binding.btnPlacePin.setOnClickListener {
            pendingAddPin = true
        }

        // 高度微调：压低 5cm
        binding.btnLower5cm.setOnClickListener {
            floorOffsetMeters -= 0.05f
            updateOffsetUi()
        }

        // 高度微调：压低 2cm
        binding.btnLower2cm.setOnClickListener {
            floorOffsetMeters -= 0.02f
            updateOffsetUi()
        }

        // 高度微调：抬高 2cm
        binding.btnRaise2cm.setOnClickListener {
            floorOffsetMeters += 0.02f
            updateOffsetUi()
        }

        // 重置微调
        binding.btnResetOffset.setOnClickListener {
            floorOffsetMeters = 0.0f
            updateOffsetUi()
        }

        binding.btnClearPins.setOnClickListener {
            for (pin in groundPins) {
                pin.detach()
            }
            groundPins.clear()
            runOnUiThread {
                binding.tvPinInfo.text = "已标定地面点: 0 个 | 准星距离: --m"
                Toast.makeText(this, "地面标桩已清空", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            requestCameraPermission()
        }

        setupGlSurfaceView()
    }

    private fun updateOffsetUi() {
        val cm = floorOffsetMeters * 100f
        val text = if (cm >= 0f) "+%.1f cm".format(cm) else "%.1f cm".format(cm)
        binding.tvOffsetInfo.text = "高度补偿偏移: $text (悬空则点 ⬇ 压低)"
        hapticDriver.triggerOneShotTap(binding.root)
    }

    private fun setupGlSurfaceView() {
        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        binding.surfaceView.setWillNotDraw(false)
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
                        // 专注水平地面追踪，关闭嘈杂的垂直面
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                        instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    }
                    configure(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ARCore Session Error: ${e.message}")
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
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

    // --- GLSurfaceView.Renderer ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread()
        groundPinRenderer.createOnGlThread()
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
        currentSession.setCameraTextureName(backgroundRenderer.textureId)

        val frame: Frame
        try {
            frame = currentSession.update()
        } catch (t: Throwable) {
            return
        }

        val camera = frame.camera
        val trackingState = camera.trackingState

        // 1. 绘制相机背景 (纯净直通/轻微终端调色，不叠加任何假网格)
        backgroundRenderer.draw(
            frame = frame,
            intensity = 0f,
            filterEnabled = true
        )

        val projMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 100.0f)
        camera.getViewMatrix(viewMatrix, 0)

        // 2. 准星对准地面检测与打点处理 (在 GL 线程执行)
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        var currentCrosshairDistance = -1f
        var isTargetingRigidGround = false

        if (trackingState == TrackingState.TRACKING) {
            val hitResults = frame.hitTest(centerX, centerY)
            for (hit in hitResults) {
                val trackable = hit.trackable
                // 工业级铁律：绝不采信临时漂动的 Point！严格只在已被收敛识别的真实物理平面 Plane 上打桩
                if (trackable is Plane && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING && trackable.trackingState == TrackingState.TRACKING) {
                    // 并且击中点必须在平面实际多边形内部
                    if (trackable.isPoseInPolygon(hit.hitPose) || trackable.isPoseInExtents(hit.hitPose)) {
                        currentCrosshairDistance = hit.distance
                        isTargetingRigidGround = true

                        // 收到 UI 按钮打点请求
                        if (pendingAddPin) {
                            pendingAddPin = false
                            // 核心：直接基于刚性平面的命中位姿创建物理 Anchor，绝对不飘
                            val anchor = hit.createAnchor()
                            groundPins.add(anchor)
                            runOnUiThread {
                                hapticDriver.triggerOneShotTap(binding.root)
                                Toast.makeText(this, "第 ${groundPins.size} 个地面标桩已钉死在地砖上", Toast.LENGTH_SHORT).show()
                            }
                        }
                        break
                    }
                }
            }
        }

        if (pendingAddPin) {
            pendingAddPin = false
            runOnUiThread {
                Toast.makeText(this, "未锁定平整地面！请将准星对准带地砖纹理的区域", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. 渲染所有已钉入地面的 3D 标桩 (死死锚定在地砖上，且实时响应高度补偿)
        if (trackingState == TrackingState.TRACKING) {
            val modelMatrix = FloatArray(16)
            for (pin in groundPins) {
                if (pin.trackingState == TrackingState.TRACKING) {
                    pin.pose.toMatrix(modelMatrix, 0)
                    // 应用用户手动校准的高度偏移量 (沿世界重力轴上下微调)
                    if (floorOffsetMeters != 0.0f) {
                        Matrix.translateM(modelMatrix, 0, 0f, floorOffsetMeters, 0f)
                    }
                    groundPinRenderer.draw(modelMatrix, viewMatrix, projMatrix)
                }
            }
        }

        // 4. UI 数据更新
        val pinCount = groundPins.size
        runOnUiThread {
            // 根据准星是否锁定在真实地面，改变准星透明度与状态提示
            if (isTargetingRigidGround) {
                binding.ivReticle.alpha = 1.0f
                binding.btnPlacePin.isEnabled = true
                binding.btnPlacePin.alpha = 1.0f
                binding.tvStatus.text = "地表已锁定！点击「标定地面点」钉入标桩"
            } else {
                binding.ivReticle.alpha = 0.35f
                binding.btnPlacePin.isEnabled = false
                binding.btnPlacePin.alpha = 0.5f
                binding.tvStatus.text = "正在寻找水平地面... 请缓慢平移手机扫描地砖"
            }

            val distStr = if (currentCrosshairDistance > 0f) "${"%.2f".format(currentCrosshairDistance)}m" else "--m"
            binding.tvPinInfo.text = "已标定地面点: $pinCount 个 | 准星当前距离: $distStr"
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
