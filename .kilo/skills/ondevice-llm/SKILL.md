---
name: ondevice-llm
description: 当任务涉及端侧模型下载、llama.cpp 集成、解释生成、:llm 独立进程或模型档位探测时使用。M5 必读。
---

# 分层（运行时探测，禁止写死机型白名单）

| 档位 | 判定 | 模型 | 体积 |
|---|---|---|---|
| T0 | MemTotal ≤ 8GB 或微基准不达标 | Qwen3-0.6B | ≈0.4GB |
| T1 | MemTotal ≥ 12GB 且基准达标 | Qwen3-1.7B（关 thinking） | ≈1.1GB |
| T2 | MemTotal ≥ 16GB 且基准优良 | Qwen3-4B「精解模式」 | ≈2.5GB |

档位 = `Build.SOC_MODEL`（API 31+）+ `/proc/meminfo` + 首跑 64-token 解码微基准；
设置页可手动覆盖并展示依据。

# 集成

- llama.cpp Android 集成为 `:llm` 模块，构建加 `-DGGML_CPU_KLEIDIAI=ON`；
  推理 Service 跑 `android:process=":llm"`。
- 模型**不随 APK 分发**：WorkManager + OkHttp Range 断点续传，ModelScope 优先，
  SHA256 校验，进度通知，可取消删除。
- 缓存键 `sha256(poem_id + prompt_ver + model_id)`；UI 固定标注"AI 生成，仅供参考"。
- 热约束：监听 `PowerManager.getThermalHeadroom`，接近节流阈值降线程并提示。
- `:llm` 崩溃自动重启并提示重试，不影响主进程。

# 待补充（M5 开工前由 Architect 定契约）

`model_catalog.json` schema、档位探测状态机、下载状态机、绑定协议、流式回调形状。
