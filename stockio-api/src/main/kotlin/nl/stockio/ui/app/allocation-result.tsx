import { safeSourceUrl, type AllocationResult } from "@/lib/allocation";

export default function AllocationResultView({ result }: { result: AllocationResult }) {
  const processed = result.status === "ALLOCATION_PROCESSED";
  return (
    <section aria-label="Allocation result" className="overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm">
      <div className={`border-b p-6 ${processed ? "border-emerald-100 bg-emerald-50" : "border-amber-100 bg-amber-50"}`}>
        <p className={`text-xs font-semibold tracking-wider ${processed ? "text-emerald-800" : "text-amber-800"}`}>{result.status.replaceAll("_", " ")}</p>
        <h2 className="mt-2 text-xl font-semibold">{processed ? "Allocation processed" : "No allocation was made"}</h2>
        <p className="mt-2 text-sm leading-6 text-stone-600">{result.message}</p>
      </div>
      <div className="space-y-6 p-6">
        {result.selectedProduct && <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-stone-500">Selected product</p>
            <p className="mt-1 font-semibold">{result.selectedProduct.title}</p>
            <p className="mt-1 font-mono text-xs text-stone-500">{result.selectedProduct.sku}</p>
          </div>
          <span className="rounded-full bg-stone-100 px-3 py-1 text-xs">
            {result.selectedProduct.equivalent ? "Equivalent selected" : "Exact title match"}
          </span>
        </div>}

        {result.allocationRun && <div>
          <h3 className="mb-3 text-sm font-semibold">Actual allocation quantities</h3>
          {result.allocationRun.allocations.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">Inventory allocation, inbound allocation, and backorders per order line</caption>
                <thead className="border-b border-stone-200 text-xs text-stone-500">
                  <tr>{["Order / Line / SKU", "Requested", "Inventory", "Inbound", "Backordered"].map((label) => <th key={label} scope="col" className="px-2 py-3 font-medium">{label}</th>)}</tr>
                </thead>
                <tbody>
                  {result.allocationRun.allocations.map((allocation) => (
                    <tr key={allocation.orderLineId} className="border-b border-stone-100">
                      <th scope="row" className="px-2 py-3 font-normal">
                        <span className="block font-medium">{allocation.orderId}</span>
                        <span className="block break-all font-mono text-xs text-stone-500">{allocation.orderLineId}</span>
                        <span className="block text-xs text-stone-500">{allocation.sku}</span>
                        <span className="mt-1 block text-xs text-stone-500">
                          {allocation.processingOutcome === "SKIPPED_ORDER_EXPIRED"
                            ? "Skipped: order expired"
                            : allocation.status?.replaceAll("_", " ")}
                        </span>
                        {allocation.sources.filter((source) => source.sourceType === "INBOUND").map((source, index) => (
                          <span key={`${source.sourceId}-${index}`} className="mt-1 block text-xs text-stone-500">
                            Shipment: {source.shipmentId ?? "unavailable"} / Line: {source.shipmentLineId ?? source.sourceId}
                            {" · "}{source.quantity} units
                          </span>
                        ))}
                      </th>
                      <td className="px-2 py-3">{allocation.requestedQuantity}</td>
                      <td className="px-2 py-3">{allocation.allocatedFromInventory}</td>
                      <td className="px-2 py-3">{allocation.allocatedFromInbound}</td>
                      <td className="px-2 py-3 font-semibold text-amber-800">
                        {allocation.processingOutcome === "SKIPPED_ORDER_EXPIRED" ? "Not created" : allocation.backorderedQuantity}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="text-sm text-stone-500">This run returned no allocation records. No quantities can be inferred.</p>}
          <p className="mt-3 text-xs leading-6 text-stone-500">
            Run: <span className="break-all font-mono">{result.allocationRun.runId}</span><br />
            Open backorder orders reported by this run: {result.allocationRun.openBackorderCount}
          </p>
        </div>}

        {result.candidates.length > 0 && <details>
          <summary className="cursor-pointer text-sm font-semibold">Product candidates ({result.candidates.length})</summary>
          <ul className="mt-3 divide-y divide-stone-100">
            {result.candidates.map((product) => <li key={product.sku} className="flex justify-between gap-3 py-3 text-sm">
              <span>{product.title}<span className="mt-1 block font-mono text-xs text-stone-500">{product.sku}</span></span>
              <span className="text-stone-600">Score {product.similarity.toFixed(3)}</span>
            </li>)}
          </ul>
          <p className="mt-2 text-xs text-stone-500">Similarity scores are not confidence probabilities.</p>
        </details>}

        <div className="border-t border-stone-100 pt-4">
          <h3 className="text-sm font-semibold">External evidence</h3>
          {result.sources.length > 0 ? (
            <ul className="mt-2 space-y-2 text-sm">
              {result.sources.map((source, index) => {
                const href = safeSourceUrl(source.url);
                return <li key={`${source.url}-${index}`}>
                  {href ? <a className="break-words text-emerald-800 underline underline-offset-4" href={href} target="_blank" rel="noopener noreferrer">{source.title || source.url}</a> : <span>{source.title || "Source URL unavailable"}</span>}
                </li>;
              })}
            </ul>
          ) : <p className="mt-2 text-sm text-stone-500">No external sources were returned. Local-only matches do not perform a new Tavily lookup.</p>}
        </div>
      </div>
    </section>
  );
}
