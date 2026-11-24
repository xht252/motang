# release 签名目录

本目录**不入库**：`*.jks` / `*.keystore` / `keystore.properties` 已由 `.gitignore` 排除。

## 首次生成（M0 一次性动作，之后固定不变）

```powershell
New-Item -ItemType Directory -Path keystore -Force
keytool -genkeypair -v `
  -keystore keystore/motang-release.jks `
  -alias motang `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -dname "CN=motang, OU=personal, O=motang, L=, ST=, C=CN"
```

## 口令注入（三选一，均不入库）

1. `~/.gradle/gradle.properties`（推荐）：
   ```properties
   MOTANG_STORE_FILE=keystore/motang-release.jks
   MOTANG_STORE_PASSWORD=***
   MOTANG_KEY_ALIAS=motang
   MOTANG_KEY_PASSWORD=***
   ```
2. 环境变量同名注入。
3. `-PMOTANG_STORE_FILE=...` 命令行传入。

## 为什么必须从 M0 固定

自签直装下，签名不一致会导致覆盖安装失败
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装，
Room 中的打卡与统计随之丢失。

## 记录指纹

```powershell
keytool -list -v -keystore keystore/motang-release.jks -alias motang
```

把 SHA-256 写入 `.kilo/memory/MILESTONES.md` 的「待填」小节。
