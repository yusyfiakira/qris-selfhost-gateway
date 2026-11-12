ALTER TABLE invoices ADD COLUMN callback_status TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE invoices ADD COLUMN callback_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN callback_http_code INTEGER;
ALTER TABLE invoices ADD COLUMN callback_last_error TEXT;
ALTER TABLE invoices ADD COLUMN callback_next_retry_at TEXT;
ALTER TABLE invoices ADD COLUMN callback_delivered_at TEXT;
ALTER TABLE invoices ADD COLUMN cancelled_at TEXT;

CREATE INDEX IF NOT EXISTS idx_invoices_callback_retry
  ON invoices (callback_status, callback_next_retry_at);

CREATE TABLE IF NOT EXISTS gateway_settings (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP)
);

ALTER TABLE device_heartbeats ADD COLUMN alerted_offline_at TEXT;
