package com.memepatrol.core

enum class AnomalyStage {
    IDLE,                  // 未标定墙面
    CALIBRATED_SEARCHING,  // 墙面已标定，等待玩家对准
    PHASE1_VINES_FADING_IN,// 初次注视：泛黄藤蔓与侧脸图案淡入
    PHASE1_STABLE,         // 图案稳定，提示声源向后方墙根移动
    PHASE2_LOOKING_AWAY,   // 玩家转开视线：声音爬行，暗中完成“人头转脸”异化
    PHASE3_FACE_REVEAL     // 玩家再次转回：人脸直勾勾注视玩家，“到墙里来”浮现！
}

class WallAnomalyStateMachine {

    var currentStage: AnomalyStage = AnomalyStage.IDLE
        private set

    var gazeDuration: Float = 0f
        private set

    var lookAwayDuration: Float = 0f
        private set

    var revealFactor: Float = 0f // 0.0 (侧脸无字) -> 1.0 (正脸凝视 + "到墙里来")
        private set

    var surfaceAlpha: Float = 0f // 墙纸整体可见度 0.0 -> 1.0
        private set

    fun onAnchorPlaced() {
        currentStage = AnomalyStage.CALIBRATED_SEARCHING
        gazeDuration = 0f
        lookAwayDuration = 0f
        revealFactor = 0f
        surfaceAlpha = 0f
    }

    fun update(isLookingAtWall: Boolean, deltaSeconds: Float) {
        when (currentStage) {
            AnomalyStage.IDLE -> {}

            AnomalyStage.CALIBRATED_SEARCHING -> {
                if (isLookingAtWall) {
                    currentStage = AnomalyStage.PHASE1_VINES_FADING_IN
                    gazeDuration += deltaSeconds
                    surfaceAlpha = (gazeDuration / 1.5f).coerceIn(0f, 1f)
                    if (gazeDuration >= 1.5f) {
                        currentStage = AnomalyStage.PHASE1_STABLE
                    }
                }
            }

            AnomalyStage.PHASE1_VINES_FADING_IN -> {
                if (isLookingAtWall) {
                    gazeDuration += deltaSeconds
                    surfaceAlpha = (gazeDuration / 1.5f).coerceIn(0f, 1f)
                    if (gazeDuration >= 1.5f) {
                        currentStage = AnomalyStage.PHASE1_STABLE
                    }
                }
            }

            AnomalyStage.PHASE1_STABLE -> {
                surfaceAlpha = 1.0f
                if (!isLookingAtWall) {
                    currentStage = AnomalyStage.PHASE2_LOOKING_AWAY
                    lookAwayDuration = 0f
                }
            }

            AnomalyStage.PHASE2_LOOKING_AWAY -> {
                surfaceAlpha = 1.0f
                if (!isLookingAtWall) {
                    lookAwayDuration += deltaSeconds
                    // 只要视线移开超过 2 秒，暗中完成形态异化蜕变
                    if (lookAwayDuration >= 2.0f) {
                        revealFactor = 1.0f
                    }
                } else {
                    // 玩家转回视线了！
                    if (revealFactor >= 1.0f) {
                        currentStage = AnomalyStage.PHASE3_FACE_REVEAL
                    } else {
                        // 移开时间太短，退回阶段 1
                        currentStage = AnomalyStage.PHASE1_STABLE
                    }
                }
            }

            AnomalyStage.PHASE3_FACE_REVEAL -> {
                surfaceAlpha = 1.0f
                revealFactor = 1.0f
            }
        }
    }

    fun reset() {
        currentStage = AnomalyStage.IDLE
        gazeDuration = 0f
        lookAwayDuration = 0f
        revealFactor = 0f
        surfaceAlpha = 0f
    }
}
