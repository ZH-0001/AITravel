# 拾途 · AI 辅助旅游行程规划

基于 Spring Boot、Vue 3 和 DeepSeek 官方 `deepseek-v4-pro` 的单城市旅行规划系统。真实模式由后端调用国内的博查（Bocha）网页搜索，再由 DeepSeek 整理候选景点、生成结构化行程；保留来源、参考费用和历史版本。

## 当前功能

- 注册登录、昵称维护、服务端会话、BCrypt、CSRF 及方案归属权限。
- 1～7 天单城市规划、必去景点、多人目的地预算与费用复算。
- 异步任务、分阶段进度、幂等提交、限流、超时和重启中断处理。
- 每日活动展示、来源链接、历史版本、AI 修改和方案逻辑删除。
- 管理员任务诊断、模型及搜索调用记录、可选费用估算。
- 明确标注的模拟演示模式，不使用密钥，不发起任何外部 AI / 搜索请求。

博查是独立网页搜索服务，需要在 [博查官网](https://bochaai.com/) 开通网页搜索 API 并获取密钥，按其实际政策计费。本项目只是提供适配代码，没有替用户注册、购买或调用付费服务。DeepSeek 的函数调用能力不等于自带网页搜索。

## 本地启动

要求 Java 21、Maven 3.9+、Node.js 20.18+。默认使用 H2 文件数据库，数据位于 `backend/.data/`，无需本地数据库账号；不会附带默认管理员或示例用户。

`backend/.env.example` 是可提交的配置模板，`backend/.env.local` 是本地实际配置，已加入 Git 忽略。若新克隆的项目没有 `backend/.env.local`，在 `backend` 目录复制 `.env.example` 并重命名为 `.env.local`。后端通过 Spring Boot 配置导入读取该文件，无需安装 dotenv 依赖。

第一个 PowerShell 窗口：

```powershell
cd D:\Codex\AITravel\backend
mvn spring-boot:run
```

第二个窗口：

```powershell
cd D:\Codex\AITravel\frontend
npm ci
npm run dev
```

打开 <http://127.0.0.1:5173>，先注册账号，再选择杭州、成都或北京体验。演示模式中的景点、价格和修改结果均为模拟，仅用于验证系统流程。其他城市会使用明确标注的通用模拟资料，不代表搜索成功。

前端代理 `/api` 至 `127.0.0.1:8080`，请通过前端地址访问，以共享同源 Cookie。后端端口默认 8080，可用 `PORT` 调整，同时修改 Vite 代理。

## 开启真实调用

在 `backend/.env.local` 填写以下三项，保存后重新启动后端：

```dotenv
TRAVEL_MODE=live
DEEPSEEK_API_KEY=你的DeepSeek密钥
BOCHA_API_KEY=你的博查密钥
```

文件采用 UTF-8 的 `KEY=value` 格式，值不加引号，注释独占一行；按 Java properties 解析，字面反斜杠写成两个反斜杠。不要在前端目录放密钥，也不要添加 `VITE_` 前缀。

也可以在启动后端的终端设置环境变量：

```powershell
$env:TRAVEL_MODE = 'live'
$env:DEEPSEEK_MODEL = 'deepseek-v4-pro'
# 在本地终端将下方占位内容替换为实际密钥，勿提交或发送到聊天中。
$env:DEEPSEEK_API_KEY = '你的DeepSeek密钥'
$env:BOCHA_API_KEY = '你的博查密钥'
mvn spring-boot:run
```

可用变量参考 `backend/.env.example`；该模板不加载，实际读取 `backend/.env.local`。从 `backend` 目录启动时读取当前目录 `.env.local`，从项目根目录启动时读取 `backend/.env.local`，统一将实际配置放在 `backend` 目录。项目根目录不再保留配置文件；请勿另外创建根目录 `.env.local`，因为当前目录同名文件优先。系统环境变量和 IDE 环境配置优先于文件，若仍有旧终端变量，需要清除或修改后重启。`live` 模式缺少密钥会明确失败，不会暗中退回演示数据。

真实任务通常包括 1～2 次博查搜索（最多 16 条去重来源）、一次 DeepSeek 资料整理和一次行程规划。搜索仅使用城市、偏好和必去名称；模型整理请求只能引用后端分配的来源编号。未找到有效来源或必去名称无法确认则失败；非必去无来源候选会过滤。模型请求关闭思考和工具，采用 JSON 输出模式，后端仍执行业务校验。

当前已用项目密钥完成一次真实闭环，博查鉴权、网页结果解析以及 DeepSeek 资料整理和规划均在该样本中成功。调用使用 `POST https://api.bochaai.com/v1/web-search`，Bearer 鉴权，参数 query/freshness/count/summary，结果读取 data.webPages.value。官方资料读取曾受工具审批服务故障阻断，仍需对照当前官方文档核验完整接口约定及价格；本地契约测试已编写，博查替换后尚未运行。参考入口：

- [DeepSeek 官方首次调用](https://api-docs.deepseek.com/)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)
- [博查官网与 API 开通入口](https://bochaai.com/)

费用不是静态内置报价。可配置 `DEEPSEEK_INPUT_PRICE`、`DEEPSEEK_OUTPUT_PRICE`（每百万 token）与 `BOCHA_PRICE_PER_REQUEST`（每次成功搜索请求），三者须使用同一 `PRICE_CURRENCY`。博查每次 HTTP 及业务状态成功且返回预期结构的请求记一个估算单位；该数值不是供应商返回的 credits。失败请求费用未知，不假定免费；未配置单价或模型未返回用量时也显示未知。最终以供应商账单为准，缓存、套餐、阶梯等特殊计价尚未自动识别。

从旧配置迁移时，删除 `TAVILY_API_KEY` 和 `TAVILY_PRICE_PER_CREDIT`，改用上方博查变量。如果曾设置 `SEARCH_BASE_URL`，应改为 `https://api.bochaai.com/v1` 或移除以采用新默认值，然后重启后端。旧日志保留当时的供应商和计价快照。

## MySQL 部署

使用 MySQL 8 创建空数据库和专用账号，然后在 `backend/.env.local` 将 `DB_PROFILE` 改为 `mysql`，填写 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，重启后端即可。默认 `DB_PROFILE=default` 使用 H2。

可执行 `backend/init-mysql.sql` 创建 UTF-8 数据库，再由应用初始化表结构。2026-10-16 已通过本机 MySQL 客户端使用本地配置创建并确认 `aitravel` 数据库及七张业务表，私有配置已切换为 `DB_PROFILE=mysql`；后端须重启后才能应用该配置。此操作未迁移 H2 数据，应用级 MySQL 生成与版本流程仍待验证。

也可以在后端终端配置（环境变量优先于文件）：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:DB_URL = 'jdbc:mysql://localhost:3306/aitravel?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:DB_USERNAME = 'aitravel'
# 另行设置 DB_PASSWORD
mvn spring-boot:run
```

启动会按 `schema.sql` 创建七张业务表。JSON 快照第一版采用 UTF-8 JSON 文本保存于 MEDIUMTEXT，兼容 H2/MySQL，业务层负责校验与解析；没有数据库 JSON 路径查询依赖。用户操作使用 MyBatis-Plus，其余跨表流程由 JDBC 和 Spring 事务处理。

数据库设计以单实例为目标；首次初始化后如修改表结构，需要显式数据库迁移，`CREATE TABLE IF NOT EXISTS` 不会修改已有表。不要直接把演示数据库复制到 MySQL。

## 管理员与安全配置

首次启动前在 `.env.local` 或环境变量中同时设置 `ADMIN_USERNAME`、`ADMIN_PASSWORD`（至少 12 字符，BCrypt 上限 72 字节），应用仅在账号不存在时创建管理员。注册请求无法指定管理员角色。配置变更不会自动覆盖已有管理员密码。

公网部署应启用 HTTPS，设置 `COOKIE_SECURE=true` 并使用同源反向代理。当前开发服务绑定本机，不作为生产服务器。密钥仅由后端使用，日志不记录完整提示词、原始响应或用户行程；来源摘要按用户归属保护。

## 验证与构建

```powershell
cd D:\Codex\AITravel\backend
mvn test
mvn package
cd D:\Codex\AITravel\frontend
npm run build
```

后端测试包括真实 Spring Security 会话、CSRF、生成与版本保存、幂等、越权、删除后来源保护、结构校验、真实模式缺密钥明确失败，以及本地 HTTP 模拟的搜索与 DeepSeek 适配测试。测试不会访问真实模型和搜索服务。

后端以 `live` 模式启动后，可在项目根目录执行 `node scripts/verify-live.mjs` 做一次真实闭环验证。脚本创建临时测试账号，提交一个杭州单人一日游任务，最多两次搜索和两次模型请求，不自动重新提交或重试。该操作会产生真实 API 费用；结果摘要写入被 Git 忽略的 `backend/.data/live-check.json`，不包含密钥或登录密码。测试账号和生成数据保留在数据库，账号凭据不保存；日常使用请自行注册账号。自动化后端测试禁用 `.env.local` 导入并使用独立内存数据库，避免个人密钥、数据库或管理员配置影响测试。

2026-10-16 验证记录：前端生产构建通过，Element Plus 分包仍有体积告警；搜索替换前的 6 项后端测试全部通过。用户终端已编译并成功启动当前后端；一次真实 DeepSeek/博查闭环任务 #34 已通过，详情见下方记录。自动化测试全量复跑和 JAR 打包此前被工具审批服务 503 故障阻断，仍待完成；MySQL 连接尚未实测。浏览器已检查首页与注册登录，完整行程展示仍待浏览器验收。一次真实成功不能代替全部测试与质量评价。

生产前端输出 `frontend/dist`，由静态服务器托管并将 `/api` 代理到后端。后端可用 `java -jar target/aitravel-0.1.0.jar` 启动。开发中的 Vite 代理配置不会自动成为生产代理。

## 已知边界

2026-10-16 17:27:49～17:28:31（北京时间），真实任务 #34 完成：杭州单人一日游，预算 500 元，8 条搜索来源、23 个候选景点，保存方案 #33 / 版本 #33，目的地参考费用 120 元。检索及资料整理约 34.7 秒，规划约 7.0 秒，校验约 2 毫秒，总阶段耗时约 41.7 秒。该费用为旅游费用估算，不是 API 账单；调用费用需核对供应商账单。结果摘要见本地 `backend/.data/live-check.json`，没有使用演示数据。测试账号为脚本临时创建，日常体验请自行注册账号。此前任务 #33 检索超时；修正 OkHttp 默认读取超时后本次成功，但不据此断言前次失败唯一原因或预设稳定性能。

- 行程、交通、开放时间和费用仅作参考；不接地图、票务、天气、预约或支付接口。
- 引用对应真实搜索来源不代表来源事实已被全面验证；名称消歧和字段提取仍需人工抽查。
- 每次修改重新检索，不跨任务缓存；联网失败不自动重试、不自动换模型，重试可能再次计费。
- 后台任务为单实例内存执行，重启会将未结束任务标为失败；没有持久化任务恢复或多实例调度。
- 本地已验证数据库为 H2；MySQL 配置和兼容建表脚本已提供，仍需使用部署数据库凭据完成实测。
- 完整需求与评价口径见根目录《说明文档.txt》；后续实验数据必须通过真实测试采集，不以模拟数据替代。
