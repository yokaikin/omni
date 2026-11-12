-- owner: java-payment

-- 幂等兜底前清理：同一 out_trade_no 存在多条待支付流水时，仅保留最新一条，其余置为支付失败
UPDATE payment p
SET status = 2,
    callback_data = COALESCE(p.callback_data, '幂等约束迁移清理：重复待支付流水')
FROM (
    SELECT id,
           out_trade_no,
           ROW_NUMBER() OVER (PARTITION BY out_trade_no ORDER BY id DESC) AS rn
    FROM payment
    WHERE status = 0 AND out_trade_no IS NOT NULL
) ranked
WHERE p.id = ranked.id AND ranked.rn > 1;

-- 同一商户订单号最多允许一条未终结（待支付/支付成功）支付流水，防止并发重复建单
CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_out_trade_no_active_unique
    ON payment(out_trade_no) WHERE status IN (0, 1);
