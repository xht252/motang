package io.github.xht252.motang

import android.app.Application
import android.os.Build
import android.util.Log
import io.github.xht252.motang.core.MotangException
import io.github.xht252.motang.core.coreVersion
import io.github.xht252.motang.core.ping
import io.github.xht252.motang.core.setRuntimeAbi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级初始化：尽早加载 native 库并把运行 ABI 传给 Rust 侧。
 *
 * 放在 Application 而非 Activity 的原因：`:llm` 独立进程（M5）也会复用该
 * Application，届时同样需要完成 native 预热。
 */
class MotangApplication : Application() {

    private val _nativeState = MutableStateFlow<NativeState>(NativeState.Loading)

    /** native 库加载结果，供 UI 以可观察方式展示。 */
    val nativeState: StateFlow<NativeState> = _nativeState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val state = loadNative()
        _nativeState.value = state
        // 关键：把验收结果打进 logcat，供 .kilo/scripts/verify-m0.ps1 的 B3 断言。
        when (state) {
            is NativeState.Ready -> Log.i(TAG, "M0 probe ok: ${state.ping} / core ${state.coreVersion}")
            is NativeState.Failed -> Log.e(TAG, "M0 probe failed: ${state.reason}")
            NativeState.Loading -> Unit
        }
    }

    private fun loadNative(): NativeState = try {
        // uniffi 生成的绑定在其类初始化时调用 Native.register；
        // 先调用 setRuntimeAbi 触发类初始化，并把真实运行 ABI 回报给 Rust。
        setRuntimeAbi(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        NativeState.Ready(
            ping = ping(),
            coreVersion = coreVersion(),
        )
    } catch (error: MotangException) {
        // 基类是 Throwable，message 可能为 null；变体子类会 override。
        NativeState.Failed(error.message ?: error.toString())
    } catch (error: UnsatisfiedLinkError) {
        NativeState.Failed("未能加载 libmotang_ffi.so：${error.message}")
    }

    sealed interface NativeState {
        data object Loading : NativeState

        /** [ping] 为绑定层探针输出；[coreVersion] 为检索核心库版本。 */
        data class Ready(val ping: String, val coreVersion: String) : NativeState

        data class Failed(val reason: String) : NativeState
    }

    private companion object {
        const val TAG = "MotangM0"
    }
}
