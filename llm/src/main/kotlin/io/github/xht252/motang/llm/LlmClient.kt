package io.github.xht252.motang.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * :llm 进程的客户端。
 *
 * 用法：`bind()` 一次后在协程里 `loadModel()` / `generate()`。
 * 所有调用都是挂起函数，内部经 Messenger 跨进程往返。
 */
class LlmClient(private val context: Context) {

    private var service: Messenger? = null
    private var bound = false

    /** 流式增量回调（每次 generateStream 前设置）。 */
    private var tokenSink: ((String) -> Unit)? = null

    private val replyHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == LlmProtocol.MSG_TOKEN) {
            tokenSink?.invoke(msg.data?.getString(LlmProtocol.KEY_TEXT).orEmpty())
        }
        if (msg.what == LlmProtocol.MSG_RESULT) {
            pending?.let { cont ->
                pending = null
                cont.resume(msg.data?.getString(LlmProtocol.KEY_TEXT).orEmpty())
            }
        }
        true
    }
    private val replyMessenger = Messenger(replyHandler)

    /** 只允许一个在途请求（推理本身串行）。 */
    private var pending: kotlinx.coroutines.CancellableContinuation<String>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(context, LlmService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(connection) }
        service = null
        bound = false
    }

    suspend fun loadModel(path: String, threads: Int = 4, nCtx: Int = 2048): String =
        send(LlmProtocol.MSG_LOAD) { data ->
            data.putString(LlmProtocol.KEY_MODEL_PATH, path)
            data.putInt(LlmProtocol.KEY_THREADS, threads)
            data.putInt(LlmProtocol.KEY_CTX, nCtx)
        }

    suspend fun generate(prompt: String, maxTokens: Int = 256, temperature: Float = 0.3f): String =
        send(LlmProtocol.MSG_GENERATE) { data ->
            data.putString(LlmProtocol.KEY_PROMPT, prompt)
            data.putInt(LlmProtocol.KEY_MAX_TOKENS, maxTokens)
            data.putFloat(LlmProtocol.KEY_TEMPERATURE, temperature)
        }

    /**
     * 流式生成。
     *
     * [onToken] 在**主线程**回调（来自 replyHandler），可直接更新 Compose 状态。
     *
     * @return 完整答案（用于写缓存）。
     */
    suspend fun generateStream(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.3f,
        onToken: (String) -> Unit,
    ): String {
        tokenSink = onToken
        return try {
            generate(prompt, maxTokens, temperature)
        } finally {
            tokenSink = null
        }
    }

    /** 语音转文字。 */
    suspend fun transcribe(
        audioPath: String,
        mmprojPath: String,
        asrModelPath: String,
        threads: Int = 4,
    ): String = send(LlmProtocol.MSG_TRANSCRIBE) { data ->
        data.putString(LlmProtocol.KEY_AUDIO_PATH, audioPath)
        data.putString(LlmProtocol.KEY_MMPROJ_PATH, mmprojPath)
        data.putString(LlmProtocol.KEY_ASR_MODEL, asrModelPath)
        data.putInt(LlmProtocol.KEY_THREADS, threads)
    }

    fun release() {
        runCatching {
            service?.send(Message.obtain(null, LlmProtocol.MSG_RELEASE))
        }
    }

    /**
     * 发送一次请求；失败（如 :llm 进程被杀）时重绑并重试一次。
     */
    private suspend fun send(what: Int, fill: (Bundle) -> Unit): String {
        sendOnce(what, fill)?.let { return it }
        // 首次失败：清掉旧连接，重新绑定后重试。
        runCatching { context.unbindService(connection) }
        service = null
        bound = false
        bind()
        kotlinx.coroutines.delay(800)
        return sendOnce(what, fill) ?: ""
    }

    /** 单次发送；失败返回 null（由调用方决定是否重试）。 */
    private suspend fun sendOnce(what: Int, fill: (Bundle) -> Unit): String? =
        suspendCancellableCoroutine { cont ->
            val target = service
            if (target == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            pending = cont
            val msg = Message.obtain(null, what).apply {
                data = Bundle().apply(fill)
                replyTo = replyMessenger
            }
            runCatching { target.send(msg) }.onFailure {
                pending = null
                cont.resume(null)
            }
        }
}