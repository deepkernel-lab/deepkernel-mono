function App() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-50">
      <header className="border-b border-slate-800 p-4">
        <h1 className="text-xl font-semibold">DeepKernel UI</h1>
        <p className="text-sm text-slate-300">Dashboard, Container Detail, Live Events, Model Explorer</p>
      </header>
      <main className="p-6 space-y-4">
        <section className="rounded-lg border border-slate-800 p-4">
          <h2 className="font-semibold">Dashboard</h2>
          <p className="text-sm text-slate-300">TODO: implement container table and KPI cards.</p>
        </section>
        <section className="rounded-lg border border-slate-800 p-4">
          <h2 className="font-semibold">Container Detail</h2>
          <p className="text-sm text-slate-300">TODO: add status, anomaly chart, verdict, policy panels.</p>
        </section>
        <section className="rounded-lg border border-slate-800 p-4">
          <h2 className="font-semibold">Live Event Stream</h2>
          <p className="text-sm text-slate-300">TODO: subscribe to /ws/events.</p>
        </section>
        <section className="rounded-lg border border-slate-800 p-4">
          <h2 className="font-semibold">Model Explorer</h2>
          <p className="text-sm text-slate-300">TODO: show model versions and comparisons.</p>
        </section>
      </main>
    </div>
  );
}

export default App;

