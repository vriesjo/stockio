ALTER TABLE stock_reservation
    ADD COLUMN required_delivery_date DATE;

UPDATE stock_reservation reservation
SET required_delivery_date = COALESCE(
    (
        SELECT MIN(record.required_delivery_date)
        FROM allocation_record record
        WHERE record.order_id = reservation.order_id
    ),
    CURRENT_DATE
);

ALTER TABLE stock_reservation
    ALTER COLUMN required_delivery_date SET NOT NULL;

CREATE TABLE inbound_supply (
    shipment_id VARCHAR(100) PRIMARY KEY,
    sku VARCHAR(64) NOT NULL REFERENCES products(sku) ON UPDATE CASCADE ON DELETE RESTRICT,
    warehouse_code VARCHAR(50) NOT NULL DEFAULT 'MAIN',
    expected_quantity INT NOT NULL CHECK (expected_quantity > 0),
    quantity_reserved INT NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    expected_arrival_date DATE NOT NULL,
    received BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_inbound_reserved_lte_expected CHECK (quantity_reserved <= expected_quantity)
);

CREATE INDEX idx_inbound_supply_sku_date
    ON inbound_supply(sku, expected_arrival_date);

CREATE TABLE inbound_reservation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id VARCHAR(100) NOT NULL,
    shipment_id VARCHAR(100) NOT NULL REFERENCES inbound_supply(shipment_id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    required_delivery_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inbound_reservation_order_shipment UNIQUE (order_id, shipment_id)
);

CREATE INDEX idx_inbound_reservation_order
    ON inbound_reservation(order_id);

INSERT INTO inbound_supply (
    shipment_id, sku, warehouse_code, expected_quantity, quantity_reserved, expected_arrival_date
)
SELECT
    source.source_id,
    record.sku,
    COALESCE(source.warehouse_location, 'MAIN'),
    SUM(source.quantity)::INT,
    SUM(source.quantity)::INT,
    MAX(source.source_date)
FROM allocation_source source
JOIN allocation_record record ON record.id = source.allocation_record_id
WHERE source.source_type = 'INBOUND'
GROUP BY source.source_id, record.sku, COALESCE(source.warehouse_location, 'MAIN')
ON CONFLICT (shipment_id) DO NOTHING;

INSERT INTO inbound_reservation (order_id, shipment_id, quantity, required_delivery_date)
SELECT
    record.order_id,
    source.source_id,
    SUM(source.quantity)::INT,
    MIN(record.required_delivery_date)
FROM allocation_source source
JOIN allocation_record record ON record.id = source.allocation_record_id
WHERE source.source_type = 'INBOUND'
GROUP BY record.order_id, source.source_id
ON CONFLICT (order_id, shipment_id) DO NOTHING;

ALTER TABLE stock_daily_snapshots
    ADD COLUMN allocated_qty INT NOT NULL DEFAULT 0;

CREATE TABLE stock_daily_lot_snapshots (
    snapshot_date DATE NOT NULL,
    sku VARCHAR(64) NOT NULL REFERENCES products(sku) ON UPDATE CASCADE ON DELETE RESTRICT,
    warehouse_code VARCHAR(50) NOT NULL DEFAULT 'MAIN',
    lot_id VARCHAR(64) NOT NULL,
    received_date DATE NOT NULL,
    opening_stock INT NOT NULL DEFAULT 0,
    inbound_qty INT NOT NULL DEFAULT 0,
    allocated_qty INT NOT NULL DEFAULT 0,
    closing_stock INT NOT NULL DEFAULT 0,
    quantity_reserved INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stock_daily_lot_snapshots PRIMARY KEY (snapshot_date, sku, warehouse_code, lot_id),
    CONSTRAINT chk_snapshot_lot_nonnegative CHECK (
        opening_stock >= 0 AND inbound_qty >= 0 AND allocated_qty >= 0
        AND closing_stock >= 0 AND quantity_reserved >= 0
        AND quantity_reserved <= closing_stock
    )
);

CREATE INDEX idx_stock_daily_lot_sku_date
    ON stock_daily_lot_snapshots(sku, snapshot_date DESC);
