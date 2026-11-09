"use client";

import { useEffect, useRef, useState, type FormEvent, type InputHTMLAttributes } from "react";
import TopNav from "../top-nav";
import {
  buildDirectAllocationRequest,
  readAllocationJobAccepted,
  readAllocationJobError,
  type AllocationJobAccepted,
  type DirectAllocationForm,
} from "@/lib/direct-allocation";

const initialForm: DirectAllocationForm = {
  orderId: "",
  customer: "",
  sku: "MAKI-KOMKOMMER",
  asyncMode: "true",
  quantity: "50",
  priority: "1",
  placedDate: "",
  requiredDeliveryDate: "",
  asOfDate: "",
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

export default function DirectAllocationPage() {
  const [form, setForm] = useState(initialForm);
  const [requestId, setRequestId] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<AllocationJobAccepted | null>(null);
  const [rawResponse, setRawResponse] = useState<unknown>(null);
  const [locationHeader, setLocationHeader] = useState<string | null>(null);
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

  function update<K extends keyof DirectAllocationForm>(key: K, value: DirectAllocationForm[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setResult(null);
    setRawResponse(null);
    setLocationHeader(null);
    setError(null);
    if (attempted.current) {
      setRequestId(crypto.randomUUID());
      attempted.current = false;
    }
  }

  let preview: string;
  try {
    preview = JSON.stringify(buildDirectAllocationRequest(form, requestId), null, 2);
  } catch (cause) {
    if (!(cause instanceof Error)) throw cause;
    preview = `Complete the form to preview the JSON request.\n${cause.message}`;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setError(null);
    let requestBody: ReturnType<typeof buildDirectAllocationRequest>;
    try {
      requestBody = buildDirectAllocationRequest(form, requestId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The form contains invalid values.");
      return;
    }
    setPending(true);
    setResult(null);
    setRawResponse(null);
    setLocationHeader(null);
    attempted.current = true;
    try {
      const response = await fetch(`/api/allocation-jobs?async=${form.asyncMode}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(requestBody),
      });
      const location = response.headers.get("Location");
      if (location) setLocationHeader(location);
      let body: unknown;
      try {
        body = await response.json();
      } catch (cause) {
        if (!(cause instanceof SyntaxError) || response.ok) throw cause;
        throw new Error(readAllocationJobError(null, response.status));
      }
      setRawResponse(body);
      if (!response.ok) throw new Error(readAllocationJobError(body, response.status));
      setResult(readAllocationJobAccepted(body));
    } catch (cause) {
      setError(
        cause instanceof SyntaxError
          ? "The allocation jobs API returned a non-JSON response."
          : cause instanceof Error ? cause.message : "The allocation jobs request failed.",
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="min-h-screen">
      <TopNav active="direct" />

      <main className="mx-auto max-w-7xl px-5 py-10 sm:px-8 sm:py-14">
        <p className="mb-3 text-xs font-bold tracking-[0.2em] text-emerald-800">DIRECT ALLOCATION API</p>
        <h1 className="max-w-3xl text-4xl font-semibold tracking-tight sm:text-5xl">
          When you know the SKU.<br /><span className="text-emerald-800">Allocate directly.</span>
        </h1>
        <p className="mt-5 max-w-2xl text-base leading-7 text-stone-600">
          Provide the SKU. Stockio then directly allocates eligible inventory using the existing allocation rules.
        </p>

        <div className="mt-10 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="min-w-0 space-y-6">
            <form onSubmit={submit} aria-label="Direct allocation request" className="rounded-2xl border border-stone-200 bg-white p-5 shadow-sm sm:p-8">
              <div className="mb-7 flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-xl font-semibold">New direct allocation job</h2>
                  <p className="mt-2 text-sm text-stone-500">All fields are required for a single order line request.</p>
                </div>
                <span className="rounded-md bg-emerald-50 px-2 py-1 font-mono text-xs text-emerald-900">POST /api/allocation/jobs</span>
              </div>

              <fieldset disabled={pending} className="min-w-0 space-y-6 disabled:opacity-70">
                <legend className="sr-only">Direct allocation details</legend>
                <div className="grid gap-5 sm:grid-cols-3">
                  <Field id="sku" label="SKU" placeholder="MAKI-KOMKOMMER" required value={form.sku} onChange={(event) => update("sku", event.target.value)} />
                  <Field id="quantity" label="Quantity" type="number" min="1" max="2147483647" step="1" required value={form.quantity} onChange={(event) => update("quantity", event.target.value)} />
                  <Field id="priority" label="Priority" type="number" min="1" max="2147483647" step="1" required value={form.priority} onChange={(event) => update("priority", event.target.value)} />
                </div>
                <div>
                  <label htmlFor="asyncMode" className="mb-2 block text-sm font-semibold">Execution mode</label>
                  <select
                    id="asyncMode"
                    name="asyncMode"
                    className="field"
                    value={form.asyncMode}
                    onChange={(event) => update("asyncMode", event.target.value as DirectAllocationForm["asyncMode"])}
                  >
                    <option value="true">Asynchronous (async=true)</option>
                    <option value="false">Inline (async=false)</option>
                  </select>
                  <p className="mt-2 text-xs leading-relaxed text-stone-500">
                    Controls the allocation-jobs query parameter. Asynchronous enqueues a job; inline runs processing in-request.
                  </p>
                </div>
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
                  <Field id="asOfDate" label="As-of date" type="date" required value={form.asOfDate} onChange={(event) => update("asOfDate", event.target.value)} hint="Must match the current business date on the service." />
                </div>
                <details className="rounded-xl border border-stone-200 p-4">
                  <summary className="cursor-pointer text-sm font-semibold">Request identity &amp; JSON preview</summary>
                  <p className="mt-3 text-xs leading-5 text-stone-500">
                    Retries of an unchanged form reuse this UUID. Editing a submitted form generates a new UUID.
                    Use the button below to submit the same data as a distinct request.
                  </p>
                  <p className="my-3 break-all font-mono text-xs">{requestId || "Preparing request identity..."}</p>
                  <button type="button" className="rounded-lg border border-stone-300 px-3 py-2 text-xs font-semibold" onClick={() => {
                    setRequestId(crypto.randomUUID());
                    attempted.current = false;
                    setResult(null);
                    setRawResponse(null);
                    setLocationHeader(null);
                    setError(null);
                  }}>Start new request</button>
                  <pre className="mt-4 max-h-96 overflow-auto rounded-lg bg-stone-950 p-4 text-xs leading-6 text-stone-200">{preview}</pre>
                </details>
                <div className="flex flex-col gap-4 border-t border-stone-100 pt-6 sm:flex-row sm:items-center sm:justify-between">
                  <p className="max-w-sm text-xs leading-5 text-stone-500">
                    This call submits an allocation job request and returns acceptance status.
                  </p>
                  <button type="submit" disabled={!requestId || pending} className="shrink-0 rounded-xl bg-emerald-900 px-6 py-3 text-sm font-semibold text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:opacity-60">
                    {pending ? "Submitting..." : "Submit allocation job"}
                  </button>
                </div>
              </fieldset>
            </form>

            <div aria-live="polite" aria-atomic="true">
              {pending && <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 text-sm leading-6 text-emerald-900">
                Submitting the allocation request. Please do not submit another request while this one is in progress.
              </p>}
              {error && <div role="alert" aria-labelledby="allocation-jobs-error-title" className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-900">
                <h2 id="allocation-jobs-error-title" className="font-semibold">Request could not be completed</h2>
                <p className="mt-2 whitespace-pre-wrap break-words">{error}</p>
                <p className="mt-3 text-xs leading-5">
                  A connection error does not guarantee cancellation. Retry unchanged to reuse the same request ID.
                </p>
              </div>}
              {result && <section className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6">
                <p className="text-xs font-semibold tracking-wider text-emerald-900">REQUEST ACCEPTED</p>
                <h2 className="mt-2 text-xl font-semibold text-emerald-950">Allocation job accepted</h2>
                <p className="mt-3 text-sm leading-6 text-emerald-900">
                  Job ID: <span className="break-all font-mono">{result.jobId}</span><br />
                  Request ID: <span className="break-all font-mono">{result.requestId}</span>
                  {locationHeader && <><br />Location: <span className="break-all font-mono">{locationHeader}</span></>}
                </p>
              </section>}
            </div>
            {rawResponse !== null && <details className="rounded-xl border border-stone-200 bg-white p-5">
              <summary className="cursor-pointer text-sm font-semibold">Raw API response</summary>
              <pre className="mt-4 max-h-96 overflow-auto whitespace-pre-wrap break-words text-xs leading-6">{JSON.stringify(rawResponse, null, 2)}</pre>
            </details>}
          </div>

          <aside className="space-y-5 lg:sticky lg:top-8">
            <section className="rounded-2xl bg-emerald-950 p-6 text-white">
              <p className="text-xs font-semibold tracking-widest text-emerald-300">REQUEST SHAPE</p>
              <h2 className="mt-3 text-xl font-semibold">AllocationRequest</h2>
              <ul className="mt-5 space-y-2 text-sm text-emerald-100/85">
                <li>runType: <span className="font-mono">NEW_DEMAND</span></li>
                <li>demandOrders: one order with one line from this form</li>
                <li>inboundShipments: empty array</li>
              </ul>
            </section>
            <section className="rounded-2xl border border-stone-200 bg-white p-6">
              <h2 className="text-sm font-semibold">What this page does</h2>
              <p className="mt-3 text-sm leading-6 text-stone-600">
                This page directly submits to the allocation jobs API contract.
                It is useful when you already know the SKU and want explicit payload control.
              </p>
            </section>
          </aside>
        </div>
      </main>
    </div>
  );
}
