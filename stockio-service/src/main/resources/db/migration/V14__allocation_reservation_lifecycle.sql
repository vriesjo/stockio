DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (
            SELECT order_id FROM stock_reservation
            UNION
            SELECT order_id FROM inbound_reservation
            UNION
            SELECT order_id FROM open_backorder
        ) active
        LEFT JOIN allocation_order_claim claim USING (order_id)
        WHERE claim.order_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Active reservations or backorders have no allocation order claim';
    END IF;
END $$;

ALTER TABLE allocation_order_claim
    ADD COLUMN reservation_status VARCHAR(40),
    ADD COLUMN reservation_created_at TIMESTAMPTZ,
    ADD COLUMN reservation_expires_at TIMESTAMPTZ,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN expired_at TIMESTAMPTZ;

UPDATE allocation_order_claim claim
SET reservation_status = 'CONFIRMED',
    reservation_created_at = run.created_at,
    confirmed_at = CURRENT_TIMESTAMP
FROM allocation_run run
WHERE run.id = claim.run_id;

ALTER TABLE allocation_order_claim
    ALTER COLUMN reservation_status SET NOT NULL,
    ALTER COLUMN reservation_created_at SET NOT NULL,
    ADD CONSTRAINT chk_reservation_lifecycle CHECK (
        (reservation_status = 'PENDING_CONFIRMATION'
            AND reservation_expires_at IS NOT NULL
            AND confirmed_at IS NULL AND expired_at IS NULL)
        OR (reservation_status = 'CONFIRMED'
            AND confirmed_at IS NOT NULL AND expired_at IS NULL)
        OR (reservation_status = 'EXPIRED'
            AND reservation_expires_at IS NOT NULL
            AND confirmed_at IS NULL AND expired_at IS NOT NULL
            AND expired_at >= reservation_expires_at)
    ),
    ADD CONSTRAINT chk_reservation_deadline CHECK (
        reservation_expires_at IS NULL OR reservation_expires_at > reservation_created_at
    );

CREATE INDEX idx_pending_reservation_deadline
    ON allocation_order_claim (reservation_expires_at, order_id)
    WHERE reservation_status = 'PENDING_CONFIRMATION';
