package com.memepatrol.ar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.memepatrol.ar.R

enum class SoundTrackType(val displayName: String) {
    SCRATCH("墙内刮擦声"),
    KNOCKING("沉闷叩击声"),
    MUTED("静音")
}

class SpatialAudioEngine(context: Context) {

    companion object {
        private const val TAG = "SpatialAudioEngine"
    }

    private val soundPool: SoundPool
    private val scratchSoundId: Int
    private val knockingSoundId: Int

    private var activeStreamId: Int = 0
    var currentTrack: SoundTrackType = SoundTrackType.SCRATCH
        private set

    private var isLoaded = false
    private var lastLeftVol = 0f
    private var lastRightVol = 0f

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        scratchSoundId = soundPool.load(context, R.raw.sfx_scratch, 1)
        knockingSoundId = soundPool.load(context, R.raw.sfx_knocking, 1)

        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                isLoaded = true
                Log.i(TAG, "Spatial audio assets loaded successfully.")
            }
        }
    }

    fun toggleTrack(): SoundTrackType {
        stop()
        currentTrack = when (currentTrack) {
            SoundTrackType.SCRATCH -> SoundTrackType.KNOCKING
            SoundTrackType.KNOCKING -> SoundTrackType.MUTED
            SoundTrackType.MUTED -> SoundTrackType.SCRATCH
        }
        return currentTrack
    }

    /**
     * 根据实时空间计算结果更新立体声平移与音量
     */
    fun updateSpatialGain(leftVol: Float, rightVol: Float) {
        lastLeftVol = leftVol
        lastRightVol = rightVol

        if (currentTrack == SoundTrackType.MUTED || !isLoaded) {
            if (activeStreamId != 0) {
                stop()
            }
            return
        }

        val soundIdToPlay = if (currentTrack == SoundTrackType.SCRATCH) scratchSoundId else knockingSoundId

        if (activeStreamId == 0) {
            // 开始循环播放
            activeStreamId = soundPool.play(soundIdToPlay, leftVol, rightVol, 1, -1, 1.0f)
        } else {
            // 实时平滑调整立体声音量
            soundPool.setVolume(activeStreamId, leftVol, rightVol)
        }
    }

    fun stop() {
        if (activeStreamId != 0) {
            soundPool.stop(activeStreamId)
            activeStreamId = 0
        }
    }

    fun release() {
        stop()
        soundPool.release()
    }
}
