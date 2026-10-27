# AGENTS.md 中文学习译本

> **来源与范围**：完整翻译仓库根目录的 [AGENTS.md](../../../AGENTS.md)，对应上游基线 `a543f993cce6e22fb37c31ae14443cf9104dbeae`。根文件保持原样。本文件是学习资料，不替代原始指令；下方“译文”与“学习解读”明确分开。贡献流程已有[官方中文说明](../../../CONTRIBUTING_zh.md)，此处不重复翻译。

## 译文

# AGENTS.md

面向 AgentScope Java 的 AI 编程指令。

## 1. 协作原则

### 先思考，再编写

- 说明假设；不确定时提问，不要猜测。
- 列出存在竞争关系的不同理解，让用户选择。
- 提出更简单的方案；质疑不必要的复杂性。
- 遇到阻塞时停止，并准确说明阻塞原因。

### 简单优先

- 只编写解决任务所需的最少代码；避免未经要求的功能、抽象、依赖和配置。
- 只为契约支持的失败情况或现实可达的执行路径添加错误处理。
- 保持差异小而集中；差异不必要地膨胀时，应缩减它。

### 精准修改

- 只修改与任务有关的行；不要顺手清理附近代码或统一其风格。
- 不要仅仅因为现有可用代码与你的写法不同，就重构它。
- 可以指出无关的死代码，但不要主动删除。
- 移除本次变更新增但未使用的导入、变量和函数。

### 可验证的结果

- 将任务转化为可检查的验收标准。
- 为每个非平凡步骤配套验证命令。
- 报告检查项、结果和剩余缺口；跳过的测试也属于缺口。

## 2. 项目地图

### 模块

- `agentscope-core/`：核心智能体 API、ReAct 循环、消息、事件、工具、中间件和状态。
- `agentscope-harness/`：工作区、记忆、子智能体、渠道及 `HarnessAgent`。
- `agentscope-extensions/`：模型、存储、协议、渠道、沙箱和框架集成。
- `agentscope-service/`：Java 服务；`service-controlplane/` 使用 Go，`frontend/` 使用 React。
- `agentscope-examples/`：可运行示例。
- `agentscope-dependencies-bom/`：第三方依赖版本。
- `agentscope-distribution/`：对外发布的 BOM 和聚合分发模块。
- `docs/`：Mintlify 文档。

### 按需阅读

- 贡献规则：[CONTRIBUTING.md](../../../CONTRIBUTING.md)。
- Harness 行为：[docs/v2/en/docs/harness/architecture.md](../../../docs/v2/en/docs/harness/architecture.md)。
- 渠道术语：[CONTEXT.md](../../../CONTEXT.md)。
- 服务开发：[agentscope-service/README.md](../../../agentscope-service/README.md)。
- 文档开发：[docs/README.md](../../../docs/README.md)。
- 命令与版本以 POM、清单文件和工作流为准。

## 3. 工程规则

### 边界

- 可复用的智能体行为属于 `agentscope-core`；更高层的能力属于 Harness。
- 提供商专属代码属于 `agentscope-extensions`；应用装配属于示例或 Service。
- 库模块不得依赖 `agentscope-examples/*`。
- 保持 core 独立于 Harness、Service 和具体扩展。
- 将 Service 网关、控制平面、数据平面与调度器的职责分开。
- 保持传输层处理器精简：解码 → 调用 → 映射。
- 新增扩展时，更新父模块、distribution 和 BOM，并运行 `.github/scripts/check-shade-and-bom-sync.sh`。

### 运行时

- 保持公共构建器、接口、序列化状态和事件载荷的兼容性。
- 保持 `call()` 与 `streamEvents()` 的最终结果一致。
- 使用 `RuntimeContext` 传递用户／会话身份；不要通过智能体字段共享会话状态。
- 使用已配置的状态存储与 Harness 工作区抽象。
- 保留 Reactor 的取消、错误传播、清理、中间件顺序及权限检查语义。
- 通过 `MiddlewareBase`／`MiddlewareChain` 扩展智能体行为；不要复制分叉智能体类。
- 不要阻塞响应式事件循环线程。

### 代码与测试

- 以 Java 17 为目标；不要使用预览特性或 switch 模式匹配；运行 Spotless；保留许可证头。
- 依赖版本放在现有 BOM／POM 层级中管理。
- 为缺陷添加回归测试，为新功能添加行为测试。
- 优先使用 JUnit 5、Mockito、Reactor `StepVerifier`、替身实现和本地测试夹具。
- 使用临时目录和可移植路径；不要使用真实凭据或提供商服务。
- 不要为只在 Windows 上偶发失败的测试削弱断言；应在测试中对应实现生产代码的保护条件。
- 在 `agentscope-service/frontend/` 修改 UI 源码；不要手工编辑生成的 `agentscope-service/service-controlplane/ui/`。
- 保持英文与中文文档一致；遵循 `.editorconfig`。

## 4. 命令

### Java

- 从仓库根目录运行，并使用 `-T1C`。
- 模块测试：`mvn -T1C -pl <module> -am test`。
- 单个测试：添加 `-Dtest=<TestClass>`，并确认该测试确实运行。
- 格式检查：`mvn -T1C -pl <module> -am spotless:check`。
- 完整检查：`mvn -B -T1C clean verify`。

### 服务与文档

- UI：`cd agentscope-service/frontend; npm test; npm run lint; npm run build`。
- Go：`cd agentscope-service/service-controlplane; make build; make test`。
- 文档：`cd docs; npm test; npm run validate; npm run broken-links`。
- 打包／部署：还需运行相关的发布检查与源码检查。

## 5. 变更流程

- 先检查 `git status` 和相关代码。
- 编码前明确公开行为、兼容性和范围。
- 实现 → 测试 → 检查完整差异。
- 用户可见行为发生变化时，更新对应的 `docs/v2` 英文和中文页面以及受影响的示例。
- 运行第 4 节中的相关检查；跨模块变更或公共契约变更需要完整检查。
- 检查是否包含无关文件、生成产物、格式错误和秘密信息。
- 未经用户授权，不要提交或推送。
- 贡献细节遵循 [CONTRIBUTING.md](../../../CONTRIBUTING.md)。

## 6. 沟通

- 先给出结果。
- 包含修改文件、检查项与结果，以及剩余缺口。
- 无关发现简要说明，并与主要结果分开。

---

## 学习解读（译者补充，非原文）

### 哪些是仓库专属规则？

模块归属、Java 17 限制、BOM 层级、指定命令、双语文档路径，以及通过 `RuntimeContext`、`MiddlewareBase` 扩展运行时，都是本仓库的具体约定。不要直接当作其他 Java 项目的强制规范。

### 哪些思想可以迁移？

- **最小改动与职责边界**：减少无关差异，有利于审查和定位回归；具体模块划分仍取决于项目。
- **验收标准先行**：将“完成”落实为可检查的行为，不把“命令启动了”当成“验证通过”。
- **兼容性不仅是方法签名**：序列化状态、事件载荷、取消与清理路径也可能影响调用方。
- **状态身份显式传递**：本仓库用 `RuntimeContext` 实现这一点；其他系统可能使用不同机制。

这些是从原文归纳的工程思路，不是原作者另行声明的通用定律。建议对照 [CONTEXT 中文译本](CONTEXT.zh-CN.md) 继续学习如何用术语表约束跨模块沟通。
