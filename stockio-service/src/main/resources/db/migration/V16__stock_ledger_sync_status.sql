CREATE TABLE stock_ledger_sync_status (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    monitoring_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_success_at TIMESTAMPTZ,
    snapshot_date DATE,
    sku_count INTEGER,
    lot_snapshot_count INTEGER,
    CONSTRAINT chk_ledger_sync_success CHECK (
        (last_success_at IS NULL AND snapshot_date IS NULL AND sku_count IS NULL AND lot_snapshot_count IS NULL)
        OR
        (last_success_at IS NOT NULL AND snapshot_date IS NOT NULL AND sku_count >= 0 AND lot_snapshot_count >= 0
            AND sku_count IS NOT NULL AND lot_snapshot_count IS NOT NULL)
    )
);

INSERT INTO stock_ledger_sync_status (id) VALUES (1);
