# Recaf Parchment 参数映射 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有 Forge 成员映射基础上，为 Recaf 增加 Parchment 参数名支持，并让 override 方法也能继承参数名。

**Architecture:** 新增一个独立的 `ParchmentMappings` 解析器，直接读取官方 `parchment.json` 并生成变量映射；同时扩展变量映射查找逻辑，使其像成员映射一样具备继承回退能力。

**Tech Stack:** Java 22/25 toolchain, Gradle, GSON, zip I/O, existing Recaf mapping/remapper pipeline

---

### Task 1: 增加 Parchment 格式测试样本

**Files:**
- Modify: `recaf-core/src/test/java/software/coley/recaf/services/mapping/format/MappingImplementationTest.java`
- Test: `recaf-core/src/test/java/software/coley/recaf/services/mapping/format/...`

**Step 1: 写最小 Parchment JSON 样本**

- 使用内联 zip 样本，包含：
  - 一个类
  - 一个方法
  - 1-2 个参数映射

**Step 2: 断言参数映射被导入**

- 校验：
  - `getMappedVariableName(...)` 返回预期参数名
  - `zip` 与 `checked.zip` 都能被解析

### Task 2: 实现 Parchment zip 解析器

**Files:**
- Create: `recaf-core/src/main/java/software/coley/recaf/services/mapping/format/ParchmentMappings.java`
- Modify: `recaf-core/src/main/java/software/coley/recaf/services/mapping/format/MappingFormatManager.java` 或 CDI 自动发现链路相关文件

**Step 1: 读取 zip 根目录的 `parchment.json`**

- 使用现有 zip / JSON 工具链
- 缺文件时抛 `InvalidMappingException`

**Step 2: 解析类 / 方法 / 参数**

- 将参数映射写入 `IntermediateMappings.addVariable(...)`
- 暂不导入 javadoc

**Step 3: 兼容 checked 口径**

- 不做文件名分支
- 同一个解析器直接按 JSON 内容工作

### Task 3: 实现变量映射继承回退

**Files:**
- Modify: `recaf-core/src/main/java/software/coley/recaf/services/mapping/MappingsAdapter.java`
- Test: `recaf-core/src/test/java/software/coley/recaf/services/mapping/MappingApplierTest.java`

**Step 1: 让变量映射查找支持继承图**

- 在当前 owner 未命中时
- 沿父类 / 接口检查同签名方法变量映射

**Step 2: 增加 override 参数名回归测试**

- 准备一组父类 / 子类 override 样本
- 断言子类方法能拿到父类提供的参数名映射

### Task 4: 集成验证

**Files:**
- Modify: `docs/plans/2026-03-06-recaf-parchment-parameter-design.md`
- Modify: `docs/plans/2026-03-06-recaf-parchment-parameter.md`

**Step 1: 运行核心构建与打包**

Run: `./gradlew :recaf-ui:shadowJar --no-configuration-cache`
Expected: 生成新的 `recaf-ui-4.0.0-SNAPSHOT-all.jar`

**Step 2: 尝试运行映射相关测试**

Run: `./gradlew :recaf-core:test --tests "*mapping*" --no-configuration-cache`
Expected: 若测试环境正常，则通过；若仍有独立测试执行器问题，明确记录

**Step 3: 做一次 headless 样本验证**

- 使用 Forge TSRG + Parchment zip 叠加导入
- 验证 `m_* / f_*` 与 `p_*` 两层都得到改善
