package com.homedungeon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallAnomalyStateMachineTest {

    @Test
    fun testNormalHorrorLoopProgression() {
        val sm = WallAnomalyStateMachine()
        assertEquals(AnomalyStage.IDLE, sm.currentStage)

        // 1. 标定锚点
        sm.onAnchorPlaced()
        assertEquals(AnomalyStage.CALIBRATED_SEARCHING, sm.currentStage)

        // 2. 玩家对准墙面注视 2 秒
        sm.update(isLookingAtWall = true, deltaSeconds = 1.0f)
        assertEquals(AnomalyStage.PHASE1_VINES_FADING_IN, sm.currentStage)

        sm.update(isLookingAtWall = true, deltaSeconds = 1.0f)
        assertEquals(AnomalyStage.PHASE1_STABLE, sm.currentStage)
        assertEquals(1.0f, sm.surfaceAlpha, 1e-4f)
        assertEquals(0.0f, sm.revealFactor, 1e-4f) // 人脸依然是侧脸隐没

        // 3. 玩家转头看别处 (isLookingAtWall = false)
        sm.update(isLookingAtWall = false, deltaSeconds = 0.5f)
        assertEquals(AnomalyStage.PHASE2_LOOKING_AWAY, sm.currentStage)

        // 移开超过 2 秒，后台完成异化蜕变
        sm.update(isLookingAtWall = false, deltaSeconds = 2.0f)
        assertEquals(1.0f, sm.revealFactor, 1e-4f)

        // 4. 玩家猛地转回头对准墙面！
        sm.update(isLookingAtWall = true, deltaSeconds = 0.1f)
        assertEquals(AnomalyStage.PHASE3_FACE_REVEAL, sm.currentStage)
        assertTrue(sm.revealFactor >= 1.0f)
    }

    @Test
    fun testLookAwayTooQuickDoesNotReveal() {
        val sm = WallAnomalyStateMachine()
        sm.onAnchorPlaced()
        sm.update(isLookingAtWall = true, deltaSeconds = 2.0f)
        assertEquals(AnomalyStage.PHASE1_STABLE, sm.currentStage)

        // 移开仅 0.5 秒就转回
        sm.update(isLookingAtWall = false, deltaSeconds = 0.5f)
        assertEquals(AnomalyStage.PHASE2_LOOKING_AWAY, sm.currentStage)

        sm.update(isLookingAtWall = true, deltaSeconds = 0.1f)
        // 蜕变未完成，退回阶段 1
        assertEquals(AnomalyStage.PHASE1_STABLE, sm.currentStage)
        assertEquals(0.0f, sm.revealFactor, 1e-4f)
    }
}
