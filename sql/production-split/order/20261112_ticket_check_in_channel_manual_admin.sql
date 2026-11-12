-- owner: java-order

-- B 端手动应急核验（console/check-in）通过 java-ticket 传入 channel=MANUAL_ADMIN，
-- 原约束缺少该渠道导致核销记录写入失败（新列违反 chk_ticket_check_in_channel）。
ALTER TABLE ticket_check_in_record DROP CONSTRAINT chk_ticket_check_in_channel;
ALTER TABLE ticket_check_in_record ADD CONSTRAINT chk_ticket_check_in_channel
    CHECK (channel IN ('GATE', 'STAFF_APP', 'WEB_BACKUP', 'MANUAL_ADMIN', 'INTERNAL_SYNC'));
