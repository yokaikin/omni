-- owner: java-order

ALTER TABLE order_snapshot ADD COLUMN IF NOT EXISTS refund_full_days_before INTEGER;
ALTER TABLE order_snapshot ADD COLUMN IF NOT EXISTS refund_partial_days_before INTEGER;
ALTER TABLE order_snapshot ADD COLUMN IF NOT EXISTS refund_partial_rate INTEGER;
