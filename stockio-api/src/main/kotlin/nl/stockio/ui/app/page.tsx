"use client";

import { useEffect, useRef, useState, type FormEvent, type InputHTMLAttributes } from "react";
import {
  buildAllocationRequest,
  readAllocationError,
  readAllocationResult,
  type AllocationForm,
  type AllocationResult,
  type ProductAllocationRequest,
} from "@/lib/allocation";
import AllocationResultView from "./allocation-result";
import TopNav from "./top-nav";

const initialForm: AllocationForm = {
  orderId: "",
  customer: "",
  productQuery: "Maki Komkommer",
  quantity: "50",
  priority: "1",
  placedDate: "",
  requiredDeliveryDate: "",
  asOfDate: "",
  allowEquivalent: true,
};

function localDate(value: Date): string {
  return [
    value.getFullYear(),
    String(value.getMonth() + 1).padStart(2, "0"),
    String(value.getDate()).padStart(2, "0"),
  ].join("-");
}

function Field({
  label, hint, id, ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string; id: string }) {
  return (
    <div>
      <label htmlFor={id} className="mb-2 block text-sm font-semibold">{label}</label>
      <input id={id} name={id} className="field" aria-describedby={hint ? `${id}-hint` : undefined} {...props} />
      {hint && <p id={`${id}-hint`} className="mt-2 text-xs leading-relaxed text-stone-500">{hint}</p>}
    </div>
  );
}

export default function AllocationPage() {
  const [form, setForm] = useState(initialForm);
  const [requestId, setRequestId] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<AllocationResult | null>(null);
  const [rawResponse, setRawResponse] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);
  const attempted = useRef(false);

  useEffect(() => {
    const today = new Date();
    const delivery = new Date(today);
    delivery.setDate(delivery.getDate() + 2);
    setForm((current) => ({
      ...current,
      placedDate: localDate(today),
      asOfDate: localDate(today),
      requiredDeliveryDate: localDate(delivery),
    }));
    setRequestId(crypto.randomUUID());
  }, []);

  function update<K extends keyof AllocationForm>(key: K, value: AllocationForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setResult(null);
    setRawResponse(null);
    setError(null);
    if (attempted.current) {
      setRequestId(crypto.randomUUID());
      attempted.current = false;
    }
  }

  let preview: string;
  try {
    preview = JSON.stringify(buildAllocationRequest(form, requestId), null, 2);
  } catch (error) {
    if (!(error instanceof Error)) throw error;
    preview = `Complete the form to preview the JSON request.\n${error.message}`;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);
    let request: ProductAllocationRequest;
    try {
      request = buildAllocationRequest(form, requestId);
    } catch (error) {
      setError(error instanceof Error ? error.message : "The form contains invalid values.");
      return;
    }
    setPending(true);
    setResult(null);
    setRawResponse(null);
    attempted.current = true;
    try {
      const response = await fetch("/api/allocate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
      });
      let body: unknown;
      try {
        body = await response.json();
      } catch (error) {
        if (!(error instanceof SyntaxError) || response.ok) throw error;
        throw new Error(readAllocationError(null, response.status));
      }
      setRawResponse(body);
      if (!response.ok) throw new Error(readAllocationError(body, response.status));
      setResult(readAllocationResult(body));
    } catch (error) {
      setError(
        error instanceof SyntaxError
          ? "The allocation API returned a non-JSON response."
          : error instanceof Error ? error.message : "The allocation request failed.",
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="min-h-screen">
      <TopNav active="agentic" />

      <main className="mx-auto max-w-7xl px-5 py-10 sm:px-8 sm:py-14">
        <p className="mb-3 text-xs font-bold tracking-[0.2em] text-emerald-800">PRODUCT ALLOCATION</p>
        <h1 className="max-w-3xl text-4xl font-semibold tracking-tight sm:text-5xl">
          Find the right product.<br /><span className="text-emerald-800">Allocate with confidence.</span>
        </h1>
        <p className="mt-5 max-w-2xl text-base leading-7 text-stone-600">
          Describe what your customer needs. Stockio resolves it to an active catalog SKU,
          then allocates eligible inventory and inbound supply using the existing allocation rules.
        </p>

        <div className="mt-10 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="min-w-0 space-y-6">
            <form onSubmit={submit} aria-label="Product allocation request" className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm sm:p-8">
              <div className="mb-7 flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-xl font-semibold">New allocation</h2>
                  <p className="mt-2 text-sm text-stone-500">All order fields are required. Review before submitting.</p>
                </div>
                <span className="rounded-md bg-emerald-50 px-2 py-1 font-mono text-xs text-emerald-900">POST /api/allocate</span>
              </div>

              <fieldset disabled={pending} className="min-w-0 space-y-6 disabled:opacity-70">
                <legend className="sr-only">Allocation details</legend>
                <div>
                  <label htmlFor="productQuery" className="mb-2 block text-sm font-semibold">Product name or description</label>
                  <textarea
                    id="productQuery"
                    name="productQuery"
                    className="field min-h-24 resize-y"
                    required
                    value={form.productQuery}
                    onChange={(event) => update("productQuery", event.target.value)}
                    aria-describedby="product-query-hint"
                    placeholder="For example: Maki Komkommer or cucumber sushi roll"
                  />
                  <p id="product-query-hint" className="mt-2 text-xs text-stone-500">
                    Enter the product phrase here; provide quantity and order metadata in the fields below.
                  </p>
                </div>
                <div className="grid gap-5 sm:grid-cols-2">
                  <Field id="quantity" label="Quantity" type="number" min="1" max="2147483647" step="1" required value={form.quantity} onChange={(event) => update("quantity", event.target.value)} />
                  <Field id="priority" label="Priority" type="number" min="1" max="2147483647" step="1" required value={form.priority} onChange={(event) => update("priority", event.target.value)} />
                </div>
                <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-emerald-100 bg-emerald-50/60 p-4">
                  <input type="checkbox" name="allowEquivalent" className="mt-1 h-4 w-4 shrink-0 accent-emerald-800" checked={form.allowEquivalent} onChange={(event) => update("allowEquivalent", event.target.checked)} />
                  <span>
                    <span className="block text-sm font-semibold">Allow an equivalent product</span>
                    <span className="mt-1 block text-xs leading-5 text-stone-600">
                      Stockio may select a sufficiently strong, unambiguous alternative. Turn off to require an exact catalog title match.
                    </span>
                  </span>
                </label>
                <div className="border-t border-stone-100 pt-6">
                  <h3 className="mb-4 text-sm font-semibold">Order details</h3>
                  <div className="grid gap-5 sm:grid-cols-2">
                    <Field id="orderId" label="Order ID" placeholder="ORDER-API-001" required value={form.orderId} onChange={(event) => update("orderId", event.target.value)} />
                    <Field id="customer" label="Customer" placeholder="Customer or company name" required value={form.customer} onChange={(event) => update("customer", event.target.value)} />
                  </div>
                </div>
                <div className="grid gap-5 sm:grid-cols-3">
                  <Field id="placedDate" label="Placed date" type="date" required value={form.placedDate} onChange={(event) => update("placedDate", event.target.value)} />
                  <Field id="requiredDeliveryDate" label="Required delivery" type="date" min={form.placedDate || undefined} required value={form.requiredDeliveryDate} onChange={(event) => update("requiredDeliveryDate", event.target.value)} />
                  <Field id="asOfDate" label="As-of date" type="date" required value={form.asOfDate} onChange={(event) => update("asOfDate", event.target.value)} hint="Date used for allocation and projection." />
                </div>
                <details className="rounded-xl border border-stone-200 p-4">
                  <summary className="cursor-pointer text-sm font-semibold">Request identity &amp; JSON preview</summary>
                  <p className="mt-3 text-xs leading-5 text-stone-500">
                    Retries of an unchanged form reuse this UUID. Editing a submitted form generates a new UUID.
                    To intentionally submit the same order again as a new request, use the button below.
                  </p>
                  <p className="my-3 break-all font-mono text-xs">{requestId || "Preparing request identity..."}</p>
                  <button type="button" className="rounded-lg border border-stone-300 px-3 py-2 text-xs font-semibold" onClick={() => {
                    setRequestId(crypto.randomUUID());
                    attempted.current = false;
                    setResult(null);
                    setRawResponse(null);
                    setError(null);
                  }}>Start new request</button>
                  <pre className="mt-4 max-h-96 overflow-auto rounded-lg bg-stone-950 p-4 text-xs leading-6 text-stone-200">{preview}</pre>
                </details>
                <div className="flex flex-col gap-4 border-t border-stone-100 pt-6 sm:flex-row sm:items-center sm:justify-between">
                  <p className="max-w-sm text-xs leading-5 text-stone-500">
                    This is a real allocation, not a preview. Shortages may become backorders.
                  </p>
                  <button type="submit" disabled={!requestId || pending} className="shrink-0 rounded-xl bg-emerald-900 px-6 py-3 text-sm font-semibold text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:opacity-60">
                    {pending ? "Resolving & allocating..." : "Allocate product"}
                  </button>
                </div>
              </fieldset>
            </form>

            <div aria-live="polite" aria-atomic="true">
              {pending && <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 text-sm leading-6 text-emerald-900">
                Resolving your product and processing the allocation. Semantic matching or a Tavily fallback may take a moment. Please do not submit another request.
              </p>}
              {error && <div role="alert" aria-labelledby="allocation-error-title" className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-900">
                <h2 id="allocation-error-title" className="font-semibold">Request could not be completed</h2>
                <p className="mt-2 whitespace-pre-wrap break-words">{error}</p>
                <p className="mt-3 text-xs leading-5">
                  A connection error does not guarantee that allocation was cancelled. Retry without editing to reuse the same request ID.
                </p>
              </div>}
              {result && <AllocationResultView result={result} />}
            </div>
            {rawResponse !== null && <details className="rounded-xl border border-stone-200 bg-white p-5">
              <summary className="cursor-pointer text-sm font-semibold">Raw API response</summary>
              <pre className="mt-4 max-h-96 overflow-auto whitespace-pre-wrap break-words text-xs leading-6">{JSON.stringify(rawResponse, null, 2)}</pre>
            </details>}
          </div>

          <aside className="space-y-5 lg:sticky lg:top-8">
            <section className="rounded-2xl bg-emerald-950 p-6 text-white">
              <p className="text-xs font-semibold tracking-widest text-emerald-300">HOW IT WORKS</p>
              <h2 className="mt-3 text-xl font-semibold">Your description, a real SKU.</h2>
              <ol className="mt-6 space-y-6">
                {[
                  ["Look locally first", "An exact catalog title match needs no AI lookup. Otherwise, Stockio searches local product and cached-knowledge vectors."],
                  ["Search only when needed", "If local matching is weak or ambiguous, Tavily provides external product evidence. Missing or ambiguous matches do not allocate."],
                  ["Use Stockio allocation rules", "The resolved SKU goes through the existing inventory and inbound-supply allocation workflow. Any shortage follows backorder rules."],
                ].map(([title, description], index) => (
                  <li key={title} className="flex gap-3">
                    <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full border border-emerald-700 text-xs text-emerald-200">{index + 1}</span>
                    <div><h3 className="text-sm font-semibold">{title}</h3><p className="mt-1 text-sm leading-6 text-emerald-100/75">{description}</p></div>
                  </li>
                ))}
              </ol>
            </section>
            <section className="rounded-2xl border border-stone-200 bg-white p-6">
              <h2 className="text-sm font-semibold">Example use case</h2>
              <blockquote className="mt-3 border-l-2 border-emerald-600 pl-4 text-lg leading-7">
                &ldquo;Hold 50 units of Maki Komkommer, or the nearest equivalent.&rdquo;
              </blockquote>
              <p className="mt-4 text-sm leading-6 text-stone-600">
                Enter <strong>Maki Komkommer</strong> as the product, <strong>50</strong> as quantity,
                and enable equivalents. Supply the real order, customer, and dates separately.
                The result shows what was actually allocated, not a promise that all 50 units were held.
              </p>
            </section>
          </aside>
        </div>
      </main>
    </div>
  );
}
