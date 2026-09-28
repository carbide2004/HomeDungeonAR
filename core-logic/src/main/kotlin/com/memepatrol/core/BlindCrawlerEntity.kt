package com.memepatrol.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

enum class EntityState {
    IDLE,       // 尚未激活 (等待识别地面)
    PATROL,     // 地面平滑游走漫游
    ALERT       // 被准星锁定警觉停滞
}

/**
 * 首发异常实体：SCP-742-J《盲爪》核心 AI 驱动器
 */
class BlindCrawlerEntity(private val random: Random = Random()) {

    var state: EntityState = EntityState.IDLE
        private set

    // 实体在世界坐标系中的实时坐标 (Y 轴严格锁死在物理地面)
    var position: Vector3 = Vector3(0f, 0f, 0f)
        private set

    // 漫游活动中心基准点
    var spawnOrigin: Vector3 = Vector3(0f, 0f, 0f)
        private set

    // 当前在水平地面上的运动速度向量 (m/s)
    private var velocityX: Float = 0f
    private var velocityZ: Float = 0f

    // 巡游目标点
    private var targetWaypointX: Float = 0f
    private var targetWaypointZ: Float = 0f

    // 状态计时器
    private var alertDuration: Float = 0f
    private var patrolSpeed: Float = 0.28f // 爬行速度约 28cm/s

    val patrolRadius: Float = 1.6f // 在中心周围 1.6 米范围内游荡，适配任何客厅

    fun spawnAt(groundPos: Vector3) {
        spawnOrigin = groundPos
        position = groundPos
        state = EntityState.PATROL
        pickNewWaypoint()
    }

    /**
     * 每一帧更新实体物理与状态
     *
     * @param deltaSeconds 帧间隔时间 (秒)
     * @param isGazedByPlayer 是否被玩家准星锁定 (cos >= 0.88)
     */
    fun update(deltaSeconds: Float, isGazedByPlayer: Boolean) {
        if (state == EntityState.IDLE) return

        if (isGazedByPlayer) {
            // 被玩家准星直接照到，立刻警觉定住
            state = EntityState.ALERT
            alertDuration += deltaSeconds
            // 停步不动
            velocityX = 0f
            velocityZ = 0f
        } else {
            // 视线离开后恢复游走
            state = EntityState.PATROL
            alertDuration = 0f

            // 向巡游目标点移动
            val dx = targetWaypointX - position.x
            val dz = targetWaypointZ - position.z
            val distToWaypoint = sqrt(dx * dx + dz * dz)

            if (distToWaypoint < 0.15f) {
                pickNewWaypoint()
            } else {
                val dirX = dx / distToWaypoint
                val dirZ = dz / distToWaypoint

                velocityX = dirX * patrolSpeed
                velocityZ = dirZ * patrolSpeed

                val nextX = position.x + velocityX * deltaSeconds
                val nextZ = position.z + velocityZ * deltaSeconds

                position = Vector3(nextX, position.y, nextZ)
            }
        }
    }

    private fun pickNewWaypoint() {
        val angle = random.nextFloat() * 2f * Math.PI.toFloat()
        val r = (0.3f + random.nextFloat() * (patrolRadius - 0.3f))
        targetWaypointX = spawnOrigin.x + r * cos(angle)
        targetWaypointZ = spawnOrigin.z + r * sin(angle)
    }

    fun reset() {
        state = EntityState.IDLE
        position = Vector3(0f, 0f, 0f)
        spawnOrigin = Vector3(0f, 0f, 0f)
        velocityX = 0f
        velocityZ = 0f
    }
}
