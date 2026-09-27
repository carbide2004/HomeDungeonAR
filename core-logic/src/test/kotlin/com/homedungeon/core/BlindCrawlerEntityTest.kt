package com.homedungeon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BlindCrawlerEntityTest {

    @Test
    fun testSpawnAndPatrolMovement() {
        val entity = BlindCrawlerEntity(Random(42))
        assertEquals(EntityState.IDLE, entity.state)

        val spawn = Vector3(0f, -0.5f, -2.0f)
        entity.spawnAt(spawn)
        assertEquals(EntityState.PATROL, entity.state)
        assertEquals(spawn.y, entity.position.y, 1e-4f)

        // 模拟运动 2 秒
        entity.update(deltaSeconds = 2.0f, isGazedByPlayer = false)

        // 位置应该发生物理移动，但 Y 轴依然锁死在地面
        assertEquals(spawn.y, entity.position.y, 1e-4f)
        assertNotEquals(spawn.x, entity.position.x, 1e-4f)

        // 移动距离不应该超出设定的安全游荡半径
        val dx = entity.position.x - spawn.x
        val dz = entity.position.z - spawn.z
        val dist = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
        assertTrue(dist <= entity.patrolRadius + 0.1f)
    }

    @Test
    fun testAlertFreezeWhenGazed() {
        val entity = BlindCrawlerEntity(Random(42))
        val spawn = Vector3(0f, 0f, 0f)
        entity.spawnAt(spawn)

        // 玩家准星照向它 (isGazed = true)
        entity.update(deltaSeconds = 0.5f, isGazedByPlayer = true)
        assertEquals(EntityState.ALERT, entity.state)

        val posDuringAlert = entity.position

        // 持续注视 1 秒，位置应该被完全定死不动
        entity.update(deltaSeconds = 1.0f, isGazedByPlayer = true)
        assertEquals(EntityState.ALERT, entity.state)
        assertEquals(posDuringAlert.x, entity.position.x, 1e-4f)
        assertEquals(posDuringAlert.z, entity.position.z, 1e-4f)

        // 视线移开，恢复巡逻
        entity.update(deltaSeconds = 0.5f, isGazedByPlayer = false)
        assertEquals(EntityState.PATROL, entity.state)
    }
}
