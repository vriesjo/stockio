-- Delete logistics data while retaining the products catalog and Flyway history.
-- Child rows are deleted before their referenced parent rows.

BEGIN;

DELETE FROM allocation_source;
DELETE FROM inbound_reservation;
DELETE FROM stock_reservation;

DELETE FROM allocation_record;
DELETE FROM open_backorder;
DELETE FROM stock_daily_lot_snapshots;
DELETE FROM stock_daily_snapshots;

DELETE FROM allocation_run;
DELETE FROM inbound_supply;
DELETE FROM stock;

COMMIT;
