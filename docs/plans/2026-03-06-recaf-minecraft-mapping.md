# Recaf Minecraft 映射适配 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 修复 Forge/Fabric Minecraft 映射导入，让官方混淆类名与成员名可以稳定还原为可读名。

**Architecture:** 保持现有映射应用链路不变，只修 `recaf-core` 中的格式解析层。Forge 继续沿用现有 `SRG/TSRG` 语义并用测试锁定；Fabric 在 `TinyV1Mappings` 中补 Yarn 伪类成员解析。

**Tech Stack:** Java 22 toolchain, Gradle, JUnit 5, mapping-io

---

### Task 1: 为 Forge/Fabric 样本补失败测试

**Files:**
- Modify: `recaf-core/src/test/java/software/coley/recaf/services/mapping/format/...`
- Test: `recaf-core/src/test/java/software/coley/recaf/services/mapping/format/...`

**Step 1: 写 Forge TSRG 样本测试**

- 新增最小样本或内联样本，断言：
  - `getMappedMethodName(..., "m_83640_", "()D") == "getTime"`
  - `getMappedClassName("com/mojang/blaze3d/Blaze3D") == null` 或符合样本语义

**Step 2: 写 Fabric Yarn Tiny 样本测试**

- 使用伪类成员样本，断言：
  - `getMappedClassName("net/minecraft/class_4494") == "net/minecraft/GlDebugInfo"`
  - `getMappedMethodName("net/minecraft/class_4494", "method_22088", inferredDescOrFallback) == "getVendor"`
  - `getMappedFieldName("net/minecraft/class_1017", "field_5045", inferredDescOrFallback) == "capState"`

**Step 3: 运行映射格式相关测试**

Run: `./gradlew :recaf-core:test --tests "*mapping*"`
Expected: 现环境若缺 JDK 22，则记录阻塞；若可运行，则至少看到新测试先失败。

### Task 2: 实现 Fabric Yarn Tiny 变体解析

**Files:**
- Modify: `recaf-core/src/main/java/software/coley/recaf/services/mapping/format/TinyV1Mappings.java`
- Modify: `recaf-core/src/main/java/software/coley/recaf/services/mapping/format/MappingFileFormat.java`

**Step 1: 识别伪类成员模式**

- 在 `TinyV1Mappings` 中增加仅针对当前 Yarn 变体的检测逻辑。

**Step 2: 拆解类/字段/方法条目**

- 将 `owner.member -> targetOwner.targetMember` 解析成真正的成员映射。
- 对构造器 `<init>` 保留 JVM 语义，不生成伪名称。

**Step 3: 保持非该变体文件继续走通用 Tiny 解析**

- 不影响普通 Tiny 文件。

### Task 3: 锁定 Forge 语义并清理边界

**Files:**
- Modify: `recaf-core/src/main/java/software/coley/recaf/services/mapping/format/TsrgMappings.java`
- Test: `recaf-core/src/test/java/software/coley/recaf/services/mapping/format/...`

**Step 1: 校对 `tsrg2 left right` 样本语义**

- 明确右侧 SRG/混淆名到左侧可读名的反向导入仍成立。

**Step 2: 只做必要收敛**

- 若测试揭示逻辑缺口，再做最小修复；没有缺口则不扩大改动面。

### Task 4: 验证与交付

**Files:**
- Modify: `docs/plans/2026-03-06-recaf-minecraft-mapping-design.md`
- Modify: `docs/plans/2026-03-06-recaf-minecraft-mapping.md`

**Step 1: 运行可执行校验**

Run: `./gradlew :recaf-core:test --tests "*mapping*"`
Expected: 通过，或明确记录当前环境缺失 `Java 22` 导致无法执行。

**Step 2: 汇总证据**

- 记录改动文件。
- 记录测试命令与结果。
- 记录仍未覆盖的风险，例如未改 UI 自动识别。
