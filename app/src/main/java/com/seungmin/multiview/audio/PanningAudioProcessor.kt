package com.seungmin.multiview.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Stereo balance and volume processor used by both ExoPlayer preview and Transformer export. */
class PanningAudioProcessor(
    volume: Float = 1f,
    pan: Float = 0f,
    muted: Boolean = false
) : BaseAudioProcessor() {
    @Volatile private var volumeValue = volume.coerceIn(0f, 1f)
    @Volatile private var panValue = pan.coerceIn(-1f, 1f)
    @Volatile private var mutedValue = muted

    fun setMix(volume: Float, pan: Float, muted: Boolean) {
        volumeValue = volume.coerceIn(0f, 1f)
        panValue = pan.coerceIn(-1f, 1f)
        mutedValue = muted
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val frames = inputBuffer.remaining() / (channels * 2)
        val output = replaceOutputBuffer(frames * 4)
        val volume = if (mutedValue) 0f else volumeValue
        val pan = panValue
        val leftGain = volume * if (pan > 0f) 1f - pan else 1f
        val rightGain = volume * if (pan < 0f) 1f + pan else 1f

        repeat(frames) {
            val left = inputBuffer.short
            val right = if (channels > 1) inputBuffer.short else left
            repeat((channels - 2).coerceAtLeast(0)) { inputBuffer.short }
            output.putShort(scale(left, leftGain))
            output.putShort(scale(right, rightGain))
        }
        output.flip()
    }

    private fun scale(sample: Short, gain: Float): Short =
        (sample.toInt() * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
