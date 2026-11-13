# 毕设验收边界与可选外部服务验收

本文区分毕设本地业务验收与需要外部账号、真实收件人或公网回调的扩展验收。项目使用本机服务和数据库即可完成主体业务演示；外部供应商验收不是本地验收的前置条件。

## 毕设本地验收

- 短信验证码使用项目本地模拟能力，固定验证码为 `666666`。登录、注册或账号安全流程按本地接口校验结果验收；不注册阿里云、不发送真实短信，也不把短信供应商投递状态列为必过项。
- 支付宝沙箱支付已接入项目。沙箱支付功能接入状态与回调、退款和浏览器业务闭环的实际验收状态分开记录；已经接入不代表回调或完整 E2E 已经跑通，也不应因短信未接入而将支付集成标为未完成。
- 邮件、对象存储等外部服务如未在当前毕设演示流程启用，可使用项目现有本地配置完成其余业务验收，不要求真实供应商投递或云端对象验收。
- 浏览器验收以页面操作结果和后端业务状态一致为准。由于业务依赖关系而跳过的 Playwright 用例应记录为“未执行/需前置条件”，不得记录为通过；不需要的真实供应商用例可记录为“不在毕设必验范围”。

本地登录验证码可按项目启动脚本默认值使用。当前支付 E2E 用例要求沙箱买家凭据、可接收回调的 HTTPS 环境及 internal token；缺少这些条件时不能据此声称沙箱异步回调或完整支付浏览器 E2E 已验收。支付沙箱集成仍可单独通过已有代码和测试证明。

本地业务浏览器 E2E 使用 `start-project.ps1` 启动时注入的模拟支付开关，不调用支付宝网络接口。启动完整本地环境后，在 `frontend` 目录运行：

```powershell
pnpm exec playwright test 10-local-business.spec.ts --project=chromium
```

通过标准是：浏览器完成选票并创建待支付订单；本地支付确认接口返回成功；订单状态变为“已支付”；后端生成电子票；订单列表和票夹页面都能显示同一订单/票号；用户能查看入场码，后台手动核销后电子票状态变为已核销。该用例不验证支付宝异步回调，也不代表真实支付宝退款已验收。

高并发购票单独使用抢票服务的真实 Redis Lua 集成验收，不通过同时打开大量浏览器来模拟并发。测试固定使用隔离 Redis key、100 个不同用户和 10 张库存：

```powershell
cd nestjs/grab-service
$env:RUN_GRAB_REDIS_INTEGRATION='1'
npm test -- --runInBand src/grab/grab-redis.integration.spec.ts
```

并发通过标准是：100 个请求同时进入准入脚本时恰好 10 个 `ACCEPTED`、90 个 `SOLD_OUT`；Redis 库存最终为 `0`；重放 10 个成功请求全部返回 `IDEMPOTENT`，且库存仍为 `0`。该测试证明 Redis 原子准入、库存不超卖和幂等重试，不替代完整订单服务压测；需要评估 HTTP 吞吐时另行使用隔离环境和压测工具。

如需验证完整 HTTP 抢票链路，可在本机使用 `scripts/load-test-grab.mjs`。该脚本会登录一批本地演示用户、创建唯一观演人、并发提交 `/api/grab/requests`、轮询抢票结果，并默认通过正常取消订单接口回收本轮创建的待支付订单。运行前必须明确设置 `LOAD_TEST_CONFIRM=YES`：

```powershell
$env:LOAD_TEST_CONFIRM='YES'
$env:LOAD_TEST_COUNT='100'
$env:LOAD_TEST_CLEANUP='true'
node scripts/load-test-grab.mjs
```

脚本默认使用隔离演示场次 `910062` / 票档 `920186`；该场次应在运行前确认库存和历史订单为空。结果至少应检查：提交失败数为 0、超时数为 0、成功订单数不超过压测前库存、售罄/限购结果符合库存预期，以及 Gateway、抢票服务、订单服务和数据库在压测后仍可用。`cleanup.failures` 必须为空，并在数据库确认订单状态与库存恢复后才能判定测试清理通过。若支付状态无法确认，取消接口会拒绝释放订单；此时不要直接改数据库，应停止扩大压测并先恢复可用的本地支付确认配置。该脚本会产生真实订单写入和取消请求，不得指向生产或共享 staging。

## 可选扩展：HTTPS staging 回调环境

只有需要实际验收支付宝异步回调时，才需要为本地 staging 准备公网可达的 HTTPS 回调地址。该步骤不属于短信接入，也不是本地毕设业务演示的前置条件。可在 WSL2 Ubuntu 中使用 Cloudflare Quick Tunnel；它会生成临时地址，隧道重启后地址会变化。

准备 Ubuntu 和 `cloudflared` 后，先启动隔离 staging 服务，再分别建立前端和 Gateway 隧道：

```bash
# 终端一：前端
cloudflared tunnel --url http://127.0.0.1:3000

# 终端二：Gateway（支付宝 notify_url 必须使用这里的 HTTPS 地址）
cloudflared tunnel --url http://127.0.0.1:8088
```

把命令输出的两个 HTTPS 地址分别写入本地未跟踪的 `frontend/e2e/.env.e2e.local`：

```text
E2E_BASE_URL=https://<frontend-quick-tunnel>
E2E_GATEWAY_URL=https://<gateway-quick-tunnel>
```

同时将支付宝沙箱配置中的 `ALIPAY_RETURN_URL` 指向前端地址，将 `ALIPAY_NOTIFY_URL` 指向 Gateway 地址并重启 `java-payment`。Quick Tunnel 不提供固定域名，不能用于长期运行或生产环境；网络断开、进程退出和地址变化都需要重新更新这两个变量。

Ubuntu 或 `cloudflared` 尚未安装时，不要把本地端口直接暴露到公网。安装包应从官方来源下载，安装完成后再执行上述命令。

## 可选扩展：真实供应商 smoke

阿里云短信未接入毕设必验流程。以下步骤仅适用于后续确需验收短信/邮件供应商投递或对象存储云端行为时；跳过该 smoke 不影响本地模拟验证码验收。

准备一个专用通知用户，并在 `java-user` 中登记受控短信号码和邮箱。短信号码必须获准接收测试短信；邮件地址应为专用测试邮箱。配置该用户只接收 staging 通知。

必需配置：

```text
E2E_BASE_URL=https://<staging-frontend>
E2E_GATEWAY_URL=https://<staging-gateway>
OMNI_E2E_RUN_SUPPLIER_SMOKE=true
OMNI_E2E_INTERNAL_TOKEN=<staging internal token>
OMNI_E2E_NOTIFICATION_USER_ID=<专用通知用户 ID>
OMNI_E2E_SMS_TEMPLATE_CODE=<已审核模板编号>
OMNI_E2E_SMS_TEMPLATE_PARAMS={"<模板变量名>":"验收值"}
OMNI_E2E_ORGANIZER_PHONE=<专用主办方账号>
OMNI_E2E_ORGANIZER_PASSWORD=<专用主办方密码>
OMNI_E2E_USER_PHONE=<无文件权限的专用普通用户>
OMNI_E2E_USER_PASSWORD=<专用普通用户密码>
```

`OMNI_E2E_SMS_TEMPLATE_PARAMS` 的键必须与阿里云审核模板完全匹配。`OMNI_E2E_NOTIFICATION_USER_ID` 对应的用户需要同时登记手机号和邮箱。staging 的短信/邮件 provider 和重试调度必须启用；状态只有变为 `SENT` 且包含供应商消息 ID、发送时间才算通过。单测或 `PENDING` 记录不代表供应商发送成功。

对象存储用例会上传一个无业务数据的最小 PDF，检查普通用户被拒绝、主办方可生成短期签名地址且下载字节一致。上传记录 ID 会写入 `runtime/e2e-state.json` 的 `supplierAssetId`；该 pending 资产按现有策略 7 天后清理。测试结束后可在 staging 确认清理任务已删除对象，不要手动删除其他资产。

在 `frontend` 目录运行：

```powershell
pnpm exec playwright test 09-supplier-acceptance.spec.ts --project=chromium
```

未设置 `OMNI_E2E_RUN_SUPPLIER_SMOKE=true` 时用例跳过；显式开启后，地址必须为 HTTPS staging，且全部必需凭据必须齐备。

## 可选扩展：支付宝沙箱回调与业务浏览器闭环

支付宝沙箱支付已接入。以下是对异步回调证据和端到端浏览器闭环的额外验收要求，不是支付集成的启用步骤。执行前需具备沙箱买家凭据、可达的 HTTPS 回调地址及本轮业务测试数据；若条件不齐，应如实记录为未执行，不应与短信验收混为一项。

支付宝验收还需注入专用沙箱买家登录名、登录密码和支付密码：

```text
OMNI_E2E_ALIPAY_BUYER_LOGON=<沙箱买家登录名>
OMNI_E2E_ALIPAY_BUYER_PASSWORD=<沙箱买家登录密码>
OMNI_E2E_ALIPAY_PAY_PASSWORD=<沙箱支付密码>
```

支付服务必须配置支付宝沙箱商户应用、签名密钥、网关和 staging 公网可达的异步回调地址。首先按顺序运行完整浏览器套件，使其创建本轮订单并完成支付、退款、核销、候补及客服流程：

支付用例会通过 internal-only 状态接口检查 `notify_id` / `sign` 对应的已验签回调证据；只看到订单支付成功不算异步回调通过。`OMNI_E2E_INTERNAL_TOKEN` 需注入 Playwright 环境以读取该脱敏状态。

```powershell
pnpm e2e
```

通过标准是沙箱异步回调验签成功、相同通知重复投递不产生重复入账、超时和退款补偿用例结果符合预期，并且 E2E 对应订单、退款、票券和客服状态都由 staging API 回读确认。只有 UI 跳转或本地 mock 通过不算支付验收。
