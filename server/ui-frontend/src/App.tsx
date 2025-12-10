import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { DashboardPage } from './pages/DashboardPage';
import { ContainerPage } from './pages/ContainerPage';
import { LiveEventsPage } from './pages/LiveEventsPage';
import { ModelExplorerPage } from './pages/ModelExplorerPage';

const NavLink = ({ to, label }: { to: string; label: string }) => {
  const location = useLocation();
  const active = location.pathname === to || location.pathname.startsWith(to + '/');
  return (
    <Link
      to={to}
      className={`px-3 py-2 rounded-md text-sm font-medium ${
        active ? 'bg-slate-800 text-sky-200' : 'text-slate-300 hover:bg-slate-800/60'
      }`}
    >
      {label}
    </Link>
  );
};

function App() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-50">
      <header className="border-b border-slate-800 px-6 py-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">DeepKernel</h1>
          <p className="text-sm text-slate-300">Runtime security · eBPF · Isolation Forest · Live triage</p>
        </div>
        <nav className="flex gap-2">
          <NavLink to="/" label="Dashboard" />
          <NavLink to="/live" label="Live Events" />
          <NavLink to="/models" label="Model Explorer" />
        </nav>
      </header>
      <main className="p-6">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/container/:id" element={<ContainerPage />} />
          <Route path="/live" element={<LiveEventsPage />} />
          <Route path="/models" element={<ModelExplorerPage />} />
        </Routes>
      </main>
    </div>
  );
}

export default App;

