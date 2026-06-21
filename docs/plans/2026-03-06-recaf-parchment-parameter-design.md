# Recaf Parchment 参数映射设计

日期：2026-03-06  
范围：在现有 Forge TSRG 成员映射基础上，新增 Parchment 参数名映射支持，并让参数映射能传播到 override 方法。

## 1. 目标

- 让 Recaf 在导入 Forge TSRG 后，额外支持 Parchment `zip` / `checked.zip` 中的参数名映射。
- 让参数名恢复不局限于 owner 精确匹配，覆盖常见 override 方法场景。
- 保持当前类/字段/方法映射链路不变，不破坏现有 Tiny / SRG / TSRG / Enigma 行为。

## 2. 现状与问题

- 当前 Recaf 已能把 `m_* / f_*` 这类成员级混淆名完整还原。
- 仍残留的 `p_*` 参数名并不是成员映射失败，而是当前 `srg-mcp-1.20.1.tsrg` 没有提供更好的参数名数据。
- Parchment 官方发布物为 `parchment.json`，位于 `zip` / `checked.zip` 根目录，方法键为：
  - `class.name`
  - `method.name`
  - `method.descriptor`
  - `parameter.index`
  - `parameter.name`
- 当前 Recaf 的变量映射链已具备基础承载能力：
  - 解析侧：`IntermediateMappings.addVariable(...)`
  - 应用侧：`WorkspaceClassRemapper -> WorkspaceBackedRemapper.mapVariableName(...)`
- 现有变量映射查找仍是 owner 精确匹配；对于子类 override 父类方法的参数名恢复，这不够。

## 3. 方案选择

采用“新增 Parchment 解析器 + 变量继承回退”的完整方案：

- 新增 `ParchmentMappings`
  - 支持导入官方 `zip`
  - 支持导入官方 `checked.zip`
  - 将参数条目转成 `IntermediateMappings` 的变量映射
- 扩展变量映射查找
  - 当当前 owner 方法未命中参数映射时
  - 沿继承图检查父类 / 接口的同签名方法变量映射
- UI 暂不做自动下载
  - 先把格式能力做完整
  - 后续若需要，再做按 MC 版本自动叠加下载

## 4. 设计细节

### 4.1 Parchment 格式导入

- 新增 `software.coley.recaf.services.mapping.format.ParchmentMappings`
- 输入：
  - `parchment-<mc>-<ver>.zip`
  - `parchment-<mc>-<ver>-checked.zip`
- 解析流程：
  - 打开 zip
  - 读取根目录 `parchment.json`
  - 遍历 `classes[].methods[].parameters[]`
  - 对每个参数调用：
    - `mappings.addVariable(owner, methodName, methodDesc, null, null, index, newName)`
- 不依赖 `mapping-io`
  - 当前 `mapping-io` 依赖中没有 Parchment reader
  - 直接用仓库现有 GSON + zip 读取即可完成第一版

### 4.2 普通 zip 与 checked.zip

- 普通 zip 提供更自然的参数名，例如 `pipeline`
- `checked.zip` 提供更“安全/可编译”的参数名，例如 `pPipeline`
- 第一版统一支持两者，不额外分成两个格式类
- 行为上只按 `parchment.json` 内容导入，不猜 zip 文件名

### 4.3 变量映射继承回退

- 扩展 `MappingsAdapter.getMappedVariableName(...)`
- 查找顺序：
  1. 当前 owner 精确匹配
  2. 若有继承图，则沿父类 / 接口检查同签名方法
  3. 返回首个命中的参数映射
- 目标：
  - `Screen.render(...)` 的参数映射能传递给 `ConfigRawScreen.render(...)`
  - 不要求“局部变量”跨层传播，只处理方法参数 / 局部变量映射的既有键模型

### 4.4 错误处理

- zip 中缺少 `parchment.json`：明确抛 `InvalidMappingException`
- JSON 结构不符合预期：明确抛 `InvalidMappingException`
- 参数条目缺 `index` 或 `name`：跳过该参数，不影响其他方法导入
- 不把 javadoc 导入当前映射链，避免扩大改动面

## 5. 验收标准

- 导入 Parchment zip 后，`p_*` 参数名可在精确 owner 匹配场景下恢复
- override 方法中的 `p_*` 参数名也可通过继承回退恢复
- 现有 Forge TSRG 成员映射行为不回退
- `zip` 与 `checked.zip` 均可导入
- 非 Parchment 映射格式行为保持不变
