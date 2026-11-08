# RikkaHub Stellar

本仓库是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的个人自用构建，在上游代码基础上集成
[Stellar](https://github.com/roro2239/Stellar) 特权框架，使助手能够以 Stellar 服务的身份执行 shell 命令。

本项目非官方发行版，不提供技术支持，不承诺与上游保持同步，亦不对使用后果承担责任。
除下述差异外，功能与行为均与上游一致；其他语言的 README（`README_ZH_CN.md` 等）为上游原文，未作修改。

- 上游仓库：<https://github.com/rikkahub/rikkahub>
- Stellar 框架：<https://github.com/roro2239/Stellar>

## 与上游的差异

### 应用标识

应用名称由 `RikkaHub` 调整为 `RikkaHub Stellar`。release 构建追加 `applicationIdSuffix ".stellar"`，
debug 构建沿用上游的 `.debug` 后缀，两种构建均可与官方版本并行安装，互不覆盖。

### 特权命令执行

新增本地工具 `stellar_exec`，通过 `Stellar.newProcess` 创建远程进程执行命令，返回标准输出、
标准错误、退出码与服务 uid。权限等级取决于 Stellar 服务的启动方式：ADB 方式等同 `adb shell`，
Root 方式等同 root。

实现上未采用 AIDL 用户服务方案，原因如下：

- `Stellar.newProcess` 在 SDK 中为 public，可直接取得 `StellarRemoteProcess`，无需绑定自定义用户服务；
- `StellarRemoteProcess` 未覆写 `Process.waitFor(long, TimeUnit)`，而该方法的默认实现依赖
  `exitValue()` 抛出 `IllegalThreadStateException` 判断进程是否存活；服务端抛出的异常经 AIDL 代理
  跨 binder 返回后类型为 `IllegalArgumentException`，会导致调用失败。故改用 SDK 提供的
  `waitForTimeout()`，并以 `alive()` 轮询作为退化路径；
- 输出由有界收集器截断（stdout 8000 字符、stderr 2000 字符），超限后继续读取并丢弃，
  避免管道写满导致子进程阻塞。

### 安全边界

新增 `StellarCommandGuard`，对下列命令实施硬性拦截：递归删除系统或用户数据根目录、格式化类工具、
`dd` 写入块设备、重启与关机、将系统分区重新挂载为可写、终止 init 进程、重定向写入块设备。

拦截逻辑与审批机制相互独立，命中后返回结构化错误，不可通过审批绕过。规则附带 18 项单元测试，
覆盖应拦截与不应拦截两类输入。

### 界面与设置

- 设置中新增 `Stellar` 页面，展示管理器安装状态、服务运行状态与授权状态，并支持发起授权；
- 设置项 `stellarAutoApprove`（默认关闭）用于关闭 `stellar_exec` 的逐次确认，不影响上述硬性拦截；
- 助手本地工具页新增 `Stellar` 开关，开启时按需发起授权。

### 平台配置

- `AndroidManifest.xml` 声明 `StellarProvider`（`authorities = ${applicationId}.stellar`）与
  `roro.stellar.permissions` meta-data，并在 `queries` 中声明 `roro.stellar.manager`，
  以满足 Android 11 及以上的包可见性要求；
- 依赖 `com.github.roro2239.Stellar-API:api:1.0.3` 与 `:provider:1.0.3`（JitPack）。

工具描述及模型可见的错误文本统一为英文并保持静态，避免动态内容破坏提示词前缀缓存。

## 构建

环境要求：JDK 21、Android SDK（compileSdk 37）、Gradle 9.6 及以上。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleGithubDebug
```

构建产物位于 `app/build/outputs/apk/github/debug/`。

上游将 `app/google-services.json` 排除在版本控制之外，本地构建需自行提供，否则
`:app:processGithubDebugGoogleServices` 将失败。

## 许可

沿用上游许可证，详见 [LICENSE](LICENSE)。
