# Codex 项目规则

## 回复与协作

- 默认使用中文回答；命令、代码、日志、异常类名和接口路径保持原文。
- 回答要简洁直接。排障问题先给结论和下一步命令，再解释原因。
- 用户可见的提示、警告、错误、注解、按钮反馈和状态说明必须使用中文；不要新增英文文案。
- 代码标识、枚举值、接口字段和日志中的技术名词可保持原文。
- 不主动提交或推送 Git。
- 任务完成后不要自动合并分支；需要合并、提交、推送时必须由用户明确要求。
- 修改代码或文档时保持最小改动；可以做对目标必要且完整的改动。不做无关重构，不回滚用户已有改动。
- 默认不删除不可逆资源。用户明确要求清理时，删除前先确认对象位于当前工作区，并只处理能证明为临时、过期或可重建的文件。

## 下载与联网

- 涉及下载依赖、拉 Docker 镜像、安装包或大规模联网操作时，不要直接执行；先说明需要下载什么，让用户自行下载或明确授权。
- 需要下载时优先提示使用镜像源，例如 npm/pnpm 使用 `https://registry.npmmirror.com`，Maven 检查本机 `settings.xml` 镜像，Docker 使用当前 Docker Desktop registry mirror。
- 不要因为依赖下载失败就擅自改 lockfile、删除 `node_modules`、清空 Maven 本地仓库或重置 Docker volume。
- 能用本地已有依赖完成的检查可以直接运行；如果命令可能触发大量依赖下载，先提醒用户。

## 当前项目口径

- Omni 是类大麦网票务平台，采用 **B 端主导、C 端参与** 模式。
- 当前推荐运行方式是 `prod-split`：五个 Java 业务服务分别连接五个 PostgreSQL database，`grab-service` 独立连接 `omni_grab`，`java-gateway` 不连接业务数据库。
- `omni_ticket` 只作为历史共享库、迁移源或 local-schema disposable 实验库，不再作为当前业务运行库。
- 当前票务运行库必须是 `omni_ticket_split`。
- 评价系统当前允许迭代，命名围绕 `activity_review` / `activity_question`；动态系统禁止恢复，不要恢复 `MomentSection`、`SocialController`、moment API 或旧 social/moment 持久化代码。
- 前端页面、入口按钮和状态必须跟后端能力对接；不要只让后端测试通过而前端不可见。

## 目录边界

```text
java/                 Java 微服务
frontend/             Next.js 前端
nestjs/grab-service/  抢票/候补服务
sql/production-split/ 生产物理拆库迁移资产
sql/local/            仅本地 disposable DB 使用
scripts/              启动、检查、导入导出和运行态验证脚本
docs/                 保留的设计、边界、运维和生产就绪文档
runtime/              本地运行时目录，内容不应提交
```

## 数据库拓扑

| 服务 | 数据库 |
|:---|:---|
| `java-user` | `omni_user` |
| `java-ticket` | `omni_ticket_split` |
| `java-order` | `omni_order` |
| `java-payment` | `omni_payment` |
| `java-notification` | `omni_notification` |
| `grab-service` | `omni_grab` |
| `java-gateway` | 不连接业务数据库 |

本机默认连接：`localhost:5432` / `postgres` / `123456`。生产环境必须通过环境变量或部署平台注入真实凭据。

## 本机启动

推荐：

```powershell
powershell -ExecutionPolicy Bypass -File start-project.ps1
```

Docker 中间件模式：

```powershell
powershell -ExecutionPolicy Bypass -File start-project.ps1 -UseDockerInfra
```

当前 `scripts/start-infra.ps1` 只检查本机 PostgreSQL 并按需启动 Docker Redis、Nacos、RabbitMQ、Elasticsearch（已运行的服务会跳过）；不会启动 PostgreSQL 容器。

Seata：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\start-seata-docker.ps1
```

网络环境变化后先刷新 Seata，再重启 `java-ticket`、`java-order`、`java-payment`。

## 微服务硬约束

- 禁止新增跨服务 Mapper、Entity、XML mapper 或 SQL join。
- `java-order` 不直接访问 user/ticket 表；通过 `java-user` 和 `java-ticket` internal API 完成用户校验、报价、库存锁定、座位锁定、确认售出和释放。
- `java-payment` 不直接访问 order/user/ticket 表；通过 `java-order` 获取/更新订单，通过 `java-user` 和 `java-ticket` 做退款审核校验。
- `java-notification` 的 `userId`、`orderId` 是 copied id，不拥有 user/order 数据。
- 所有新增 internal API 必须校验 `X-Internal-Token`。
- internal token 配置名是 `internal.api.token` 或环境变量 `INTERNAL_API_TOKEN`。
- `OrderService.createOrder()` 写 order-owned `order_snapshot`，订单列表/详情展示不跨查 ticket 表。
- `ActivityService.listActivities()` 已批量查询优化，避免恢复 N+1 查询。

## 前端约定

- 使用 `src/lib/api.ts` 的 `request<T>()` 统一请求封装。
- 认证状态在 `src/lib/auth.ts` 管理，token key 为 `omni_token`，user key 为 `omni_user`。
- 登录、订单、支付、抢票页不允许 mock/offline 降级；后端不可用时直接显示失败。
- B 端 `console/*` 页面使用 `ConsoleLayout` 侧边栏布局。
- 品牌色：`#ff1268`。
- 前端运行要求 Node `>=24`。

## 验收命令

边界验收：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-microservice-boundaries.ps1
```

Windows 本地运行该边界脚本时，会自动给 Java 测试 JVM 注入 `-Djdk.net.unixdomain.tmpdir=D:/Project/omni/runtime`，用于规避 JDK/Netty loopback 兼容问题；该参数不写入生产或 Docker 运行配置。

单项检查：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-service-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-cross-owner-fks.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-local-schema-profiles.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-local-schema-sql.ps1
powershell -ExecutionPolicy Bypass -File scripts/check-production-split-sql.ps1
```

生产拆库 runtime verifier：

```powershell
$env:PGPASSWORD='123456'
powershell -ExecutionPolicy Bypass -File scripts/verify-production-split-runtime.ps1 -UserHost localhost -TicketHost localhost -OrderHost localhost -PaymentHost localhost -NotificationHost localhost -GrabHost localhost -TargetDatabaseByService 'ticket=omni_ticket_split'
```

前端检查：

```powershell
cd frontend
pnpm typecheck
```

当前数据库连接检查：

```powershell
$env:PGPASSWORD='123456'
psql -h localhost -p 5432 -U postgres -d postgres -t -A -c "SELECT datname, application_name, state FROM pg_stat_activity WHERE datname LIKE 'omni%' ORDER BY datname, application_name, state;"
```

正常情况下不应出现业务 JDBC 连接到 `omni_ticket`。

## 生产资产

- 生产拆库设计：`docs/operations/2026-05-20-production-physical-db-split-design.md`
- 生产拆库实施计划：`docs/operations/2026-05-20-production-physical-db-split-implementation.md`
- Cutover checklist：`docs/operations/production-db-split-cutover-checklist.md`
- Manifest：`sql/production-split/manifest.json`
- 导出脚本：`scripts/export-production-split.ps1`
- 导入脚本：`scripts/import-production-split.ps1`
- Runtime verifier：`scripts/verify-production-split-runtime.ps1`
- 生产环境变量清单：`docs/production-readiness/production-env-vars.md`

真实 staging / production cutover 必须先完成 Production Migration Safety Gate、cutover checklist 和人工批准。

## 清理纪律

- 不要提交本地备份、数据库 dump、运行 artifact、`backups/`、`runtime/` 内容、`outputs/`、`unit-test/`、`.playwright-mcp/`。
- 一次性执行计划、历史流水和工具会话文件不要放在源码根目录。
- 如果需要保留验收证据，放到外部归档、issue、发布记录或明确的长期 runbook。
- `sql/local/*` 禁止进入 staging / production 迁移链路。
