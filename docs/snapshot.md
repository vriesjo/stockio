# Allocation-Generated Stock Snapshots

The allocation workflow maintains two related but distinct views of inventory:

- `stock` is the current physical baseline, with on-hand and reserved quantities by SKU, warehouse, and lot.
- `stock_daily_snapshots` and `stock_daily_lot_snapshots` are the aggregate and lot-level daily trajectory generated from allocation, inbound supply, and backorder data.

Historical snapshots are retained. Each allocation or backorder-fulfillment run rebuilds the projection from today forward using live stock as its baseline; it does not attempt to reconstruct past physical stock from current quantities.

## Snapshot semantics

`closing_stock` is the number of physical units remaining at the end of the day:

```text
closing_stock = opening_stock + inbound_qty - allocated_qty
demand_qty = allocated_qty + shortage_qty
```

Allocated units leave physical stock on their `requiredDeliveryDate`, not when the allocation is created. A backordered quantity contributes to the shortage on its required delivery date, but it is not subtracted from physical stock. The closing balance carries into the next day's opening balance.

The aggregate table is keyed by `(snapshot_date, sku, warehouse_code)`. The companion `stock_daily_lot_snapshots` table stores the corresponding lot-level opening balance, inbound quantity, allocated quantity, closing balance, reserved quantity, and receipt date. Keeping lot identity is necessary to synchronize FIFO inventory correctly.

## Allocation flow

`calculate_allocation` and `fulfill_open_backorders` reserve persisted inventory and inbound supply, save allocation/backorder records, and rebuild snapshots. Inbound shipments are persisted so later allocation calls can use any unreceived quantity that remains unreserved. A successful allocation from a shipment is represented by an inbound reservation until the shipment is received.

When a shipment arrives, `process_daily_stock` transfers its saved lot-level closing balance into live `stock` and converts its remaining future inbound reservations into stock reservations. It also applies today's projected closing balances and removes stock reservations whose required delivery date has arrived.

An order allocated from a new shipment is recorded as fulfilled from inbound supply, not inventory, in that allocation run. Any unallocated shipment quantity becomes available live stock after receipt and can be allocated from inventory by a later order. See the [per-order quantity example](stock_interaction.md#per-order-quantity-flow) for how on-hand, reserved, and available quantities change.

## Daily stock synchronization

The `process_daily_stock` MCP tool accepts:

```json
{
  "snapshotDate": "YYYY-MM-DD"
}
```

`snapshotDate` must be today, and today's snapshots must already exist. The service checks that aggregate closing balances equal their lot-level totals before synchronizing. Repeating the operation is safe; it neither recalculates movements nor applies an allocation twice.

The implementation is in `StockProjectionService` and `DailyStockLedgerService`; Flyway migration `V8__allocation_stock_projections.sql` adds the supporting schema.

## Table responsibilities

| Table | Role |
| --- | --- |
| `products` | Product catalog and SKU reference data. |
| `stock` | Current physical quantity and future reservations by lot. |
| `stock_reservation` | Order allocation against existing stock lots. |
| `inbound_supply` | Expected receipts and available/reserved shipment quantities. |
| `inbound_reservation` | Order allocation against supply not yet received. |
| `stock_daily_snapshots` | Aggregate daily stock trajectory. |
| `stock_daily_lot_snapshots` | Lot-level trajectory used for accurate live-stock synchronization. |
