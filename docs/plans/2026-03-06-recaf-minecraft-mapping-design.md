# Recaf Minecraft 映射适配设计

日期：2026-03-06
范围：修复 Forge/Fabric 映射导入后无法将 Minecraft 官方混淆名还原为可读名称的问题；本次不调整 UI 交互。

## 1. 目标

- 让 Forge `SRG/TSRG/TSRG2` 映射在导入后稳定将 `m_*/f_*` 等混淆成员名还原为可读名。
- 让当前样本中的 Fabric `yarn-*.tiny` 在导入后稳定将 `class_*/method_*/field_*` 还原为可读名。
- 通过回归测试锁定真实样本语义，避免后续重构再次破坏 Minecraft 映射支持。

## 2. 当前问题

- Forge 侧已有 `SrgMappings` 与 `TsrgMappings`，但缺少针对真实 Minecraft 样本的回归测试，行为可靠性没有被锁定。
- Fabric 侧当前 `TinyV1Mappings` / `TinyV2Mappings` 直接复用通用 `mapping-io` 解析流程。
- 工作区中的 `yarn-1.21.4.tiny` 并非 Recaf 当前假设的“标准成员行”形式；它将成员编码为伪类名，例如：
  - `CLASS net/minecraft/class_1018.method_4469 net/minecraft/CapabilityTracker.disable`
  - `CLASS net/minecraft/class_1017.field_5045 net/minecraft/BlendFuncState.capState`
- 这会导致现有 Tiny 解析器把成员映射误当作类映射导入，反编译时自然无法把成员名还原为可读名。

## 3. 方案选择

采用“修解析器与测试，不先改 UI”的最小可验证方案：

- Forge：
  - 保留现有 `SrgMappings` / `TsrgMappings` 主体行为。
  - 用真实样本补测试，确认 `tsrg2 left right` 这类文件仍按 Minecraft 语义反向导入。
- Fabric：
  - 为当前 Yarn Tiny 变体补专门解析逻辑，将伪类成员条目拆解为类、方法、字段映射。
  - 不伪造不存在的 `official -> named` 映射；仅在文件确实提供该关系时建立该关系。

## 4. 设计细节

### 4.1 Fabric Tiny 变体解析

- 在 `TinyV1Mappings` 中增加面向 Minecraft Yarn 样本的解析分支。
- 先尝试识别是否存在伪类成员模式：
  - 成员格式：`owner.member`
  - 构造器格式：`owner.<init>`
- 当识别到该模式时：
  - `owner -> targetOwner` 作为类映射。
  - `owner.method_xxx -> targetOwner.targetMethod` 拆成方法映射。
  - `owner.field_xxx -> targetOwner.targetField` 拆成字段映射。
  - `owner.<init> -> targetOwner.(descriptor)` 仅用于恢复构造器签名对应的方法名 `<init>`，不引入伪名称。
- 若文件不符合该变体，继续走现有通用 Tiny 解析。

### 4.2 Forge 样本回归

- 保持 `TsrgMappings` 的 Minecraft 反向语义：
  - 输入右侧 SRG/混淆名，输出左侧可读名。
- 增加真实样本回归，锁定：
  - `m_83640_ -> getTime`
  - 典型字段 `f_* -> readableName`

### 4.3 风险控制

- 不修改 `MappingApplicationPane` 与菜单交互，避免把解析修复和 UI 语义变更绑在一起。
- 不新增 fallback 行为；仅在检测到明确的 Minecraft Tiny 变体时切换专门解析。
- 对无法可靠拆解的 Yarn 行保持失败显式化，不静默导入错误映射。

## 5. 验收标准

- 导入 Forge `srg-mcp-*.tsrg` 后，类/字段/方法的可读名可被正确应用。
- 导入 Fabric `yarn-*.tiny` 后，`class_*`、`method_*`、`field_*` 可被正确还原。
- 回归测试覆盖 Forge 与 Fabric 两类真实样本的关键查找。
- 不引入新的 UI 配置项，不改变普通非 Minecraft 映射的现有导入方式。
