CREATE TEMP TABLE legacy_order_metadata ON COMMIT DROP AS
SELECT r.order_id, r.customer, r.sku, r.priority, r.placed_date,
       r.requested_quantity, r.required_delivery_date
FROM allocation_record r
JOIN allocation_order_claim c ON c.order_id = r.order_id AND c.run_id = r.run_id
UNION
SELECT item->>'orderId', item->>'customer', item->>'sku', (item->>'priority')::INT,
       (item->>'placedDate')::DATE, (item->>'requestedQuantity')::INT,
       (item->>'requiredDeliveryDate')::DATE
FROM allocation_sku_operation op
CROSS JOIN LATERAL jsonb_array_elements(op.result_json::JSONB) item
JOIN allocation_order_claim c ON c.order_id = item->>'orderId' AND c.run_id = op.run_id
UNION
SELECT b.order_id, b.customer, b.sku, b.priority, b.placed_date,
       b.original_quantity, b.required_delivery_date
FROM open_backorder b;

DO $$
BEGIN
    IF EXISTS (
        SELECT c.order_id FROM allocation_order_claim c
        LEFT JOIN legacy_order_metadata m ON m.order_id = c.order_id
        GROUP BY c.order_id
        HAVING COUNT(m.order_id) <> 1
    ) OR EXISTS (
        SELECT 1 FROM allocation_order_claim c
        JOIN legacy_order_metadata m USING (order_id)
        WHERE c.sku <> m.sku
    ) THEN
        RAISE EXCEPTION 'Missing or ambiguous metadata for legacy order lines; reconcile incomplete runs before upgrading';
    END IF;
    IF EXISTS (
        SELECT 1 FROM stock_reservation r
        JOIN legacy_order_metadata m USING (order_id)
        JOIN stock s ON s.id = r.stock_id WHERE s.sku <> m.sku
    ) OR EXISTS (
        SELECT 1 FROM inbound_reservation r
        JOIN legacy_order_metadata m USING (order_id)
        JOIN inbound_supply s USING (shipment_id) WHERE s.sku <> m.sku OR s.received
    ) OR EXISTS (
        SELECT 1 FROM stock s
        WHERE s.quantity_reserved <> COALESCE(
            (SELECT SUM(r.quantity) FROM stock_reservation r WHERE r.stock_id = s.id), 0)
    ) OR EXISTS (
        SELECT 1 FROM inbound_supply s
        WHERE s.quantity_reserved <> COALESCE(
            (SELECT SUM(r.quantity) FROM inbound_reservation r WHERE r.shipment_id = s.shipment_id), 0)
    ) THEN
        RAISE EXCEPTION 'Legacy reservation SKUs or source counters are inconsistent';
    END IF;
END $$;

ALTER TABLE allocation_order_claim RENAME TO demand_order;
ALTER TABLE demand_order
    ADD COLUMN customer VARCHAR(200),
    ADD COLUMN priority INT,
    ADD COLUMN placed_date DATE;

UPDATE demand_order o SET customer = m.customer, priority = m.priority, placed_date = m.placed_date
FROM legacy_order_metadata m WHERE m.order_id = o.order_id;

ALTER TABLE demand_order
    ALTER COLUMN customer SET NOT NULL,
    ALTER COLUMN priority SET NOT NULL,
    ALTER COLUMN placed_date SET NOT NULL,
    ADD CONSTRAINT chk_order_priority CHECK (priority > 0),
    DROP COLUMN sku;

CREATE TABLE demand_order_line (
    order_line_id VARCHAR(100) PRIMARY KEY,
    order_id VARCHAR(100) NOT NULL REFERENCES demand_order(order_id),
    sku VARCHAR(64) NOT NULL REFERENCES products(sku),
    requested_quantity INT NOT NULL CHECK (requested_quantity > 0),
    required_delivery_date DATE NOT NULL,
    sequence_number INT NOT NULL CHECK (sequence_number >= 0),
    legacy_order_id VARCHAR(100) UNIQUE,
    CONSTRAINT uq_order_line_parent UNIQUE (order_line_id, order_id),
    CONSTRAINT uq_order_line_sequence UNIQUE (order_id, sequence_number)
);

INSERT INTO demand_order_line
    (order_line_id, order_id, sku, requested_quantity, required_delivery_date, sequence_number, legacy_order_id)
SELECT order_id, order_id, sku, requested_quantity, required_delivery_date, 0, order_id
FROM legacy_order_metadata;

CREATE TABLE inbound_shipment (
    shipment_id VARCHAR(100) PRIMARY KEY
);
INSERT INTO inbound_shipment SELECT shipment_id FROM inbound_supply;

ALTER TABLE inbound_reservation DROP CONSTRAINT inbound_reservation_shipment_id_fkey;
ALTER TABLE inbound_supply
    ADD COLUMN shipment_line_id VARCHAR(100),
    ADD COLUMN lot_id VARCHAR(64),
    ADD COLUMN legacy_shipment_id VARCHAR(100) UNIQUE;
UPDATE inbound_supply SET shipment_line_id = shipment_id,
    lot_id = CASE WHEN LENGTH(shipment_id) <= 64 THEN shipment_id ELSE gen_random_uuid()::TEXT END,
    legacy_shipment_id = shipment_id;
ALTER TABLE inbound_supply
    DROP CONSTRAINT inbound_supply_pkey,
    ALTER COLUMN shipment_line_id SET NOT NULL,
    ALTER COLUMN lot_id SET NOT NULL,
    ADD PRIMARY KEY (shipment_line_id),
    ADD FOREIGN KEY (shipment_id) REFERENCES inbound_shipment(shipment_id),
    ADD CONSTRAINT uq_supply_line_parent UNIQUE (shipment_line_id, shipment_id),
    ADD CONSTRAINT uq_supply_lot UNIQUE (sku, warehouse_code, lot_id);
CREATE INDEX idx_supply_shipment ON inbound_supply(shipment_id);

ALTER TABLE stock_reservation ADD COLUMN order_line_id VARCHAR(100);
UPDATE stock_reservation SET order_line_id = order_id;
ALTER TABLE stock_reservation
    ALTER COLUMN order_line_id SET NOT NULL,
    DROP CONSTRAINT uq_stock_reservation_order_lot,
    ADD CONSTRAINT uq_stock_reservation_order_lot UNIQUE (order_line_id, stock_id),
    ADD FOREIGN KEY (order_line_id, order_id) REFERENCES demand_order_line(order_line_id, order_id);

ALTER TABLE inbound_reservation
    ADD COLUMN order_line_id VARCHAR(100),
    ADD COLUMN shipment_line_id VARCHAR(100);
UPDATE inbound_reservation SET order_line_id = order_id, shipment_line_id = shipment_id;
ALTER TABLE inbound_reservation
    ALTER COLUMN order_line_id SET NOT NULL,
    ALTER COLUMN shipment_line_id SET NOT NULL,
    DROP CONSTRAINT uq_inbound_reservation_order_shipment,
    ADD CONSTRAINT uq_inbound_reservation_order_shipment UNIQUE (order_line_id, shipment_line_id),
    ADD FOREIGN KEY (order_line_id, order_id) REFERENCES demand_order_line(order_line_id, order_id),
    ADD FOREIGN KEY (shipment_line_id, shipment_id) REFERENCES inbound_supply(shipment_line_id, shipment_id);
CREATE INDEX idx_inbound_reservation_line ON inbound_reservation(shipment_line_id);

ALTER TABLE open_backorder ADD COLUMN order_line_id VARCHAR(100);
UPDATE open_backorder SET order_line_id = order_id;
ALTER TABLE open_backorder
    ALTER COLUMN order_line_id SET NOT NULL,
    DROP CONSTRAINT open_backorder_pkey,
    ADD PRIMARY KEY (order_line_id),
    ADD FOREIGN KEY (order_line_id, order_id) REFERENCES demand_order_line(order_line_id, order_id);
CREATE INDEX idx_backorder_order ON open_backorder(order_id);

ALTER TABLE allocation_record
    ADD COLUMN order_line_id VARCHAR(100),
    ADD COLUMN processing_outcome VARCHAR(40) NOT NULL DEFAULT 'PROCESSED',
    ALTER COLUMN status DROP NOT NULL;
UPDATE allocation_record SET order_line_id = order_id;
ALTER TABLE allocation_record
    ALTER COLUMN order_line_id SET NOT NULL,
    DROP CONSTRAINT uq_allocation_record_run_order,
    ADD CONSTRAINT uq_allocation_record_run_order UNIQUE (run_id, order_line_id),
    ADD FOREIGN KEY (order_line_id, order_id) REFERENCES demand_order_line(order_line_id, order_id),
    ADD CONSTRAINT chk_allocation_processing CHECK (
        (processing_outcome = 'PROCESSED' AND status IS NOT NULL)
        OR (processing_outcome = 'SKIPPED_ORDER_EXPIRED' AND status IS NULL
            AND allocated_from_inventory = 0 AND allocated_from_inbound = 0 AND backordered_quantity = 0)
    );

ALTER TABLE allocation_source
    ADD COLUMN shipment_id VARCHAR(100),
    ADD COLUMN shipment_line_id VARCHAR(100);
UPDATE allocation_source SET shipment_id = source_id, shipment_line_id = source_id
WHERE source_type = 'INBOUND';

ALTER TABLE allocation_run ADD COLUMN contract_version INT NOT NULL DEFAULT 1;
ALTER TABLE allocation_run ALTER COLUMN contract_version SET DEFAULT 2;
ALTER TABLE allocation_run ADD COLUMN documents_initialized BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE allocation_run ALTER COLUMN documents_initialized SET DEFAULT FALSE;
