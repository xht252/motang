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
                val maxTokens = msg.data.getInt(LlmProtocol.KEY_MAX_TOKENS, 256)
                val temperature = msg.data.getFloat(LlmProtocol.KEY_TEMPERATURE, 0.3f)
                val reply = msg.replyTo
                worker.execute {
                    val started = System.currentTimeMillis()
                    val text = runCatching { LlmNative.generate(prompt, maxTokens, temperature) }
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

            else -> false
        }
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        worker.execute { runCatching { LlmNative.releaseModel() } }
        worker.shutdown()
        super.onDestroy()
    }

    private fun replyMessage(data: Bundle): Message =
        Message.obtain(null, LlmProtocol.MSG_RESULT).apply { this.data = data }

    private companion object {
        const val TAG = "MotangLlmService"
    }
}
