-- owner: java-ticket

ALTER TABLE activity ADD COLUMN IF NOT EXISTS refund_full_days_before INTEGER NOT NULL DEFAULT 30;
ALTER TABLE activity ADD COLUMN IF NOT EXISTS refund_partial_days_before INTEGER NOT NULL DEFAULT 7;
ALTER TABLE activity ADD COLUMN IF NOT EXISTS refund_partial_rate INTEGER NOT NULL DEFAULT 80;

UPDATE activity
SET refund_full_days_before = COALESCE(refund_full_days_before, 30),
    refund_partial_days_before = COALESCE(refund_partial_days_before, 7),
    refund_partial_rate = COALESCE(refund_partial_rate, 80);

ALTER TABLE activity DROP CONSTRAINT IF EXISTS ck_activity_refund_policy;
ALTER TABLE activity ADD CONSTRAINT ck_activity_refund_policy
    CHECK (refund_full_days_before > refund_partial_days_before
        AND refund_partial_days_before > 0
        AND refund_partial_rate > 0
        AND refund_partial_rate <= 100);
