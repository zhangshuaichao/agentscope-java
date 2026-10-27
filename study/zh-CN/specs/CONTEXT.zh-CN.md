# 渠道集成上下文：中文学习译本

> **来源与范围**：完整翻译仓库根目录的 [CONTEXT.md](../../../CONTEXT.md)，对应上游基线 `a543f993cce6e22fb37c31ae14443cf9104dbeae`。根文件保持原样。原文定义的是本项目的渠道集成术语，不代表所有产品的统一用法。下方“译文”与“学习解读”分开；关于服务和协议的描述忠实保留原文语境，未作为最新外部产品信息另行核验。

## 译文

# 渠道集成上下文

本术语表定义 AgentScope 集成中使用的消息渠道概念，包括多租户 Agent 平台中的个人微信渠道。

## 渠道概念

**渠道（Channel）**：
一种运行时适配器，从一个提供商接收消息，将消息路由到 AgentScope，再将回复发送回该提供商。
_避免混用_：渠道资源（Channel resource）、连接器（connector）。

**渠道扩展（Channel extension）**：
一个可复用的、提供商专属的模块，实现标准 Channel 接口和提供商协议，但不负责产品账号、持久化策略或用户工作流。
_避免混用_：渠道资源（Channel resource）、托管连接（managed connection）。

**渠道资源（Channel resource）**：
由产品负责管理的配置与生命周期记录，对应一个已配置的 Channel 实例，包含路由和启用状态。
_避免混用_：渠道扩展（Channel extension）、外部账号（external account）。

**个人微信（Personal Weixin）**：
平台用户通过腾讯官方 ClawBot 服务授权、用于与 Agent 对话的个人微信账号。
_避免混用_：WeCom、企业微信（enterprise WeChat）、非官方微信协议（unofficial WeChat protocol）。

**iLink**：
官方微信 ClawBot 服务；已授权的个人微信账号通过该服务交换机器人消息。
_避免混用_：webhook 协议（webhook protocol）、iPad 协议（iPad protocol）。

**平台用户（Platform user）**：
Agent 平台中经过身份认证的人，在一个公司内由 `compId + userId` 组合标识。
_避免混用_：微信用户（Weixin user）、发送者（sender）。

**微信账号（Weixin account）**：
由一位平台用户授权的外部个人微信身份；它与该用户的平台身份不同。
_避免混用_：平台账号（platform account）、渠道（channel）。

**微信连接（Weixin connection）**：
由产品负责管理的关系，连接一个渠道资源、授权该资源的平台用户，以及一个外部微信账号。
_避免混用_：渠道扩展（Channel extension）、登录会话（login session）、账号文件（account file）。

**渠道绑定（Channel binding）**：
一种路由规则，为通过渠道资源接收的消息选择逻辑上的 AgentScope 目标。
_避免混用_：微信连接（Weixin connection）、登录会话（login session）。

**提供商登录会话（Provider login session）**：
用于推进一次二维码授权尝试的短期 iLink 状态；它的过期独立于微信连接。
_避免混用_：渠道会话（Channel session）、Agent 会话（Agent session）、微信连接（Weixin connection）。

**运行时凭据（Runtime credential）**：
iLink 在授权后签发的秘密信息，运行中的 Channel 使用它代表一个微信账号调用 iLink。
_避免混用_：上下文令牌（Context token）、凭据引用（credential reference）、二维码（QR code）。

**会话对端（Peer）**：
用于构建 AgentScope 会话键的对话另一方，可以是个人用户，也可以是群聊。
_避免混用_：发送者（sender）。

**上下文令牌（Context token）**：
iLink 提供的对话上下文，用于将回复关联到入站微信对话。
_避免混用_：会话令牌（session token）、访问令牌（access token）。

**更新游标（Update cursor）**：
微信账号接收下一批 iLink 消息时使用的续传位置。
_避免混用_：同步令牌（sync token）、消息 ID（message ID）。

## 运行时投递概念

**回复投递（Reply delivery）**：
将 Agent 回复发送回产生入站消息的会话对端。它由宿主平台负责；平台持久化回复并独立重试投递，而不是重试那条已经接收的入站消息。
_避免混用_：发件箱（outbox）、发送（send）、分派（dispatch）。

**提供商回执（Provider receipt）**：
iLink 接受回复并准备投递时返回的标识符；它是提供商已接收消息的持久证据，而不只是本地进程已接收的证据。
_避免混用_：本地确认（local ack）、投递 ID（delivery ID）、消息 ID（message ID）。

**分派失败（Dispatch failure）**：
一条已接收的入站消息未能完成端到端处理：Agent 运行失败，或者无法将消息交给宿主平台执行回复投递。
_避免混用_：投递失败（delivery failure）。

**投递失败（Delivery failure）**：
提供商反复拒绝一条回复。此时 Agent 已经运行，因此投递失败绝不会重放入站消息。
_避免混用_：分派失败（dispatch failure）、发送错误（send error）。

**放弃处理的消息（Abandoned message）**：
一条已接收但持续分派失败的入站消息；为它设置终止标记（tombstone），而不是无限重试，并按与已完成消息相同的保留策略进行清理。
_避免混用_：失败消息（failed message）、丢弃消息（dropped message）。

---

## 学习解读（译者补充，非原文）

### 仓库专属词汇，不是通用名词替换表

“避免混用”是在本项目中要求保留语义边界，并不是说被列出的英文词在其他系统里不能使用。尤其不要把所有 channel、session、token 都译成同一种对象：

| 需要区分的对象 | 原文强调的边界 |
| --- | --- |
| Channel / Channel extension / Channel resource | 运行时适配器 / 可复用实现模块 / 产品配置与生命周期记录 |
| Platform user / Weixin account / Peer | 平台身份 / 外部授权身份 / 会话对端 |
| Provider login session / Weixin connection | 一次短期授权过程 / 产品持有的连接关系 |
| Runtime credential / Context token / Update cursor | 调用服务的秘密凭据 / 回复的对话关联上下文 / 拉取消息的续传位置 |
| Dispatch failure / Delivery failure | 入站处理未完成 / Agent 已完成但回复被提供商拒绝 |

### 可以迁移的设计思路

1. **把协议适配与产品管理分开**：适配器处理协议；账号关系、路由和生命周期记录由产品层管理。这是从术语职责划分中归纳的思路。
2. **把处理与投递分开**：回复重试不应自动变成重新执行 Agent，否则可能重复调用工具并产生副作用。这里的具体重试规则以本项目原文为准。
3. **按证据判断状态**：本地确认不等于提供商回执；提供商接收也不等于对端已经阅读。后者是帮助理解边界的补充，不是原文新增的回执保证。
4. **有限重试需要终态**：原文的 abandoned 状态既终止无限重试，也保留可按策略清理的记录，并非静默丢弃。

继续阅读：[工程规范译本](AGENTS.zh-CN.md)、[已有贡献中文说明](../../../CONTRIBUTING_zh.md)。
