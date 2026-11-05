interface TopNavProps {
  active: "agentic" | "direct";
}

export default function TopNav({ active }: TopNavProps) {
  const itemClass = (isActive: boolean) => [
    "rounded-md px-3 py-2 text-sm font-medium transition",
    isActive ? "bg-emerald-900 text-white" : "text-stone-600 hover:bg-stone-100",
  ].join(" ");

  return (
    <header className="border-b border-stone-200 bg-white/90">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-5 py-5 sm:px-8">
        <a href="/" className="flex items-center gap-3 text-xl font-bold tracking-tight" aria-label="Stockio home">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-emerald-900 text-lg text-white" aria-hidden="true">s.</span>
          stockio<span className="font-normal text-stone-400">/</span>
          <span className="hidden text-sm font-normal tracking-normal text-stone-500 sm:inline">Allocation workspace</span>
        </a>
        <nav aria-label="Allocation flows" className="flex items-center gap-2">
          <a href="/" className={itemClass(active === "agentic")}>Agentic allocation</a>
          <a href="/direct-allocation" className={itemClass(active === "direct")}>Direct allocation API</a>
        </nav>
      </div>
    </header>
  );
}
