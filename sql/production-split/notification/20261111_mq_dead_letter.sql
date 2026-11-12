-- owner: java-notification

CREATE TABLE IF NOT EXISTS mq_dead_letter (
    id BIGSERIAL PRIMARY KEY,
    source_queue VARCHAR(120) NOT NULL,
    dead_letter_queue VARCHAR(120) NOT NULL,
    payload TEXT NOT NULL,
    failure_message VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    replay_count INTEGER NOT NULL DEFAULT 0,
    replayed_at TIMESTAMP NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mq_dead_letter_status_time
    ON mq_dead_letter(status, created_time DESC);
