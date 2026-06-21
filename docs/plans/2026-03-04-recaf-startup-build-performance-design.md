# Recaf 启动与构建性能优化设计（不改功能）

日期：2026-03-04
范围：仅性能优化，不修改业务功能与 UI 交互语义。

## 1. 目标与约束

目标：
- 提升 Recaf 启动体感速度。
- 提升本地 Gradle 构建吞吐与二次构建速度。

约束：
- 功能行为保持不变。
- 失败时可快速回退。
- 保持 Windows 环境可复现。

## 2. 当前基线

- 启动基线：`Initializing Recaf` -> `Initialization: No plugins found` 约 1135ms。
- JVM 快启实验：加入 `-XX:TieredStopAtLevel=1` 后约 927ms（约 -179ms）。
- Gradle 配置耗时：`gradlew -q help --no-daemon` 约 6.0~6.5s。
- 编译风险：默认 `TARGET_VERSION=22` 在当前主机无 JDK22；需显式指定 `TARGET_VERSION=25`。

## 3. 方案选择（已确认）

采用“方案1（高收益低风险）”：
- 启动：在 `run_recaf.bat` 增加可控快启 JVM 参数（默认开启，可关闭）。
- 构建：在 `gradle.properties` 增加并行/配置缓存/文件系统监听/Daemon/JVM 参数。
- 工具链：新增 `build_fast.bat`，固化 `TARGET_VERSION=25` 与快速构建参数。

## 4. 设计细节

### 4.1 启动路径

- 保持 `javaw + start` 后台启动与自动关终端。
- 默认启用快启参数：`-XX:TieredStopAtLevel=1`。
- 提供环境变量开关：`RECAF_FAST_START=0` 时关闭该参数。
- 继续固定中文 JVM Locale 参数，保证中文显示一致性。

### 4.2 构建路径

在 `gradle.properties` 增加：
- `org.gradle.parallel=true`
- `org.gradle.configuration-cache=true`
- `org.gradle.configuration-cache.problems=warn`
- `org.gradle.vfs.watch=true`
- `org.gradle.daemon=true`
- `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED`

### 4.3 快速构建脚本

新增 `build_fast.bat`：
- 自动优先使用 `C:\Java\jdk-25`（或 `RECAF_JAVA_HOME` / `JAVA_HOME`）。
- 默认 `TARGET_VERSION=25`（可由外部覆盖）。
- 无参默认执行 `:recaf-ui:shadowJar -x test --parallel --configuration-cache --build-cache`。
- 有参时透传任务参数。

## 5. 错误处理与回退

- 启动失败：脚本打印 Java 路径与退出码。
- 构建失败：保留原 `gradlew.bat`，`build_fast.bat` 为增量入口，不破坏原流程。
- 回退策略：删除新增参数或脚本即可回退到原行为。

## 6. 验收标准

- 启动：`run_recaf.bat` 可启动 GUI 且终端立即返回。
- 中文：语言配置保持 `zh_CN`，界面文本为中文。
- 构建：`build_fast.bat -q help` 成功并输出耗时；二次执行不慢于首次。
- 功能：不修改 Java 源业务逻辑。
