package io.github.xht252.motang.ui.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 录音并写成 WAV（16 kHz / 单声道 / 16 bit）。
 *
 * 为什么不用 MediaRecorder：它不产出 WAV，而 mtmd 的音频塔需要可解析的
 * PCM 容器；AudioRecord 直接拿 PCM，再补 44 字节 WAV 头最省事。
 *
 * 采样率固定 16 kHz：Qwen3-ASR 期望 16 kHz，避免再做重采样。
 */
object VoiceRecorder {

    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /**
     * 录制 [durationMs] 毫秒并写入 [out]。
     *
     * @return 成功返回文件；无权限或录音失败返回 null。
     */
    suspend fun recordToWav(
        context: Context,
        out: File,
        durationMs: Int = 5_000,
    ): File? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return@withContext null
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 2)

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        }.getOrNull() ?: return@withContext null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }

        val totalBytes = SAMPLE_RATE * 2 * (durationMs / 1000)
        val pcm = ByteArray(totalBytes)
        var written = 0

        try {
            record.startRecording()
            while (written < totalBytes) {
                val n = record.read(pcm, written, minOf(bufferSize, totalBytes - written))
                if (n <= 0) break
                written += n
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }

        if (written <= 0) return@withContext null

        out.parentFile?.mkdirs()
        writeWav(out, pcm, written)
        out
    }

    /** 手写 44 字节 WAV 头。 */
    private fun writeWav(file: File, pcm: ByteArray, length: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataLen = length
            val byteRate = SAMPLE_RATE * 2
            val header = ByteArray(44)

            fun putAscii(offset: Int, text: String) {
                text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
            }
            fun putIntLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
                header[offset + 2] = ((value shr 16) and 0xFF).toByte()
                header[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun putShortLE(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }

            putAscii(0, "RIFF")
            putIntLE(4, 36 + dataLen)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putIntLE(16, 16)
            putShortLE(20, 1)           // PCM
            putShortLE(22, 1)           // mono
            putIntLE(24, SAMPLE_RATE)
            putIntLE(28, byteRate)
            putShortLE(32, 2)           // block align
            putShortLE(34, 16)          // bits
            putAscii(36, "data")
            putIntLE(40, dataLen)

            raf.write(header)
            raf.write(pcm, 0, length)
        }
    }
}
