CREATE TABLE IF NOT EXISTS invoices (
  id TEXT PRIMARY KEY NOT NULL,
  external_order_id TEXT NOT NULL,
  base_amount INTEGER NOT NULL,
  unique_code INTEGER NOT NULL,
  total_amount INTEGER NOT NULL,
  qr_string TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  callback_url TEXT,
  metadata_json TEXT,
  expires_at TEXT NOT NULL,
  paid_at TEXT,
  created_at TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP)
);

CREATE INDEX IF NOT EXISTS idx_invoices_pending_amount
  ON invoices (status, total_amount, expires_at);

CREATE INDEX IF NOT EXISTS idx_invoices_external_order
  ON invoices (external_order_id);

CREATE TABLE IF NOT EXISTS mutation_logs (
  id TEXT PRIMARY KEY NOT NULL,
  amount INTEGER NOT NULL,
  provider_hint TEXT,
  raw_text TEXT,
  matched_invoice_id TEXT,
  outcome TEXT NOT NULL,
  device_id TEXT,
  created_at TEXT NOT NULL DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE IF NOT EXISTS device_heartbeats (
  device_id TEXT PRIMARY KEY NOT NULL,
  app_version TEXT,
  battery_pct INTEGER,
  network_type TEXT,
  last_seen_at TEXT NOT NULL
);
