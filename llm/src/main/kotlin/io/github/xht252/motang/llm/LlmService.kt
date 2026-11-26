package io.github.xht252.motang.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.Executors

/**
 * 端侧推理 Service。
 *
 * 运行在 `android:process=":llm"` 独立进程：推理 OOM 不会连带杀死 UI 进程。
 * 通过 [Messenger] 做跨进程通信，协议见 [LlmProtocol]。
 *
 * 线程模型：所有推理在单线程池串行执行（llama.cpp 上下文不是线程安全的）。
 */
class LlmService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "motang-llm-worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            LlmProtocol.MSG_LOAD -> {
                val path = msg.data.getString(LlmProtocol.KEY_MODEL_PATH).orEmpty()
                val threads = msg.data.getInt(LlmProtocol.KEY_THREADS, 4)
                val ctx = msg.data.getInt(LlmProtocol.KEY_CTX, 2048)
                val reply = msg.replyTo
                worker.execute {
                    val error = runCatching { LlmNative.loadModel(path, threads, ctx) }
                        .getOrElse { it.message ?: "load failed" }
                    if (error.isNotEmpty()) Log.e(TAG, "loadModel: $error") else Log.i(TAG, "model ready")
                    reply?.send(Bundle().apply {
                        putString(LlmProtocol.KEY_TEXT, error)
                    }.let { replyMessage(it) })
                }
                true
            }

            LlmProtocol.MSG_GENERATE -> {
                val prompt = msg.data.getString(LlmProtocol.KEY_PROMPT).orEmpty()
                var maxTokens = msg.data.getInt(LlmProtocol.KEY_MAX_TOKENS, 256)
                val temperature = msg.data.getFloat(LlmProtocol.KEY_TEMPERATURE, 0.3f)
                val reply = msg.replyTo
                // 热约束：接近节流阈值时缩短生成长度，避免持续高温降频。
                val headroom = thermalHeadroom() 
                if (headroom != null && headroom >= 0.8f) {
                    Log.w(TAG, "thermal headroom=$headroom，缩短生成长度") 
                    maxTokens = (maxTokens / 2).coerceAtLeast(64)
                }
                worker.execute {
                    val started = System.currentTimeMillis()
                    val text = runCatching {
                        LlmNative.generateStream(prompt, maxTokens, temperature) { delta ->
                            reply?.send(tokenMessage(delta))
                        }
                    }
                        .getOrElse {
                            Log.e(TAG, "generate failed", it)
                            ""
                        }
                    Log.i(TAG, "generated ${text.length} chars in ${System.currentTimeMillis() - started} ms")
                    reply?.send(replyMessage(Bundle().apply {
                        putString(LlmProtocol.KEY_TEXT, text)
                    }))
                }
                true
            }

            LlmProtocol.MSG_RELEASE -> {
                worker.execute { runCatching { LlmNative.releaseModel() } }
                true
            }

            LlmProtocol.MSG_TRANSCRIBE -> {
                val audio = msg.data.getString(LlmProtocol.KEY_AUDIO_PATH).orEmpty()
                val mmproj = msg.data.getString(LlmProtocol.KEY_MMPROJ_PATH).orEmpty()
                val asrModel = msg.data.getString(LlmProtocol.KEY_ASR_MODEL).orEmpty()
                val threads = msg.data.getInt(LlmProtocol.KEY_THREADS, 4)
                val reply = msg.replyTo
                worker.execute {
                    val text = runCatching { LlmNative.transcribe(audio, mmproj, asrModel, threads) }
                        .getOrElse {
                            Log.e(TAG, "transcribe failed", it)
                            ""
                        }
                    reply?.send(replyMessage(Bundle().apply {
                        putString(LlmProtocol.KEY_TEXT, text)
                    }))
                }
                true
            }

            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        worker.execute { runCatching { LlmNative.releaseModel() } }
        worker.shutdown()
        super.onDestroy()
    }

    private fun tokenMessage(text: String): Message =
        Message.obtain(null, LlmProtocol.MSG_TOKEN).apply {
            data = Bundle().apply { putString(LlmProtocol.KEY_TEXT, text) }
        }
    private fun replyMessage(data: Bundle): Message =
        Message.obtain(null, LlmProtocol.MSG_RESULT).apply { this.data = data }

    /**
     * 热余量（0..1，越大越接近节流）。
     *
     * API 30+ 才有 getThermalHeadroom；低版本返回 null 表示不参与约束。
     */
    private fun thermalHeadroom(): Float? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            pm?.getThermalHeadroom(10)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "MotangLlmService"
    }
}
