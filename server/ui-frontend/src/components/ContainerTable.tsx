import { Container } from '../types';

type Props = {
  containers: Container[];
  onSelect?: (id: string) => void;
};

const badge = (label: string, color: string) => (
  <span className={`px-2 py-1 rounded-full text-xs font-semibold ${color}`}>{label}</span>
);

export function ContainerTable({ containers, onSelect }: Props) {
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-800 bg-slate-900/50">
      <table className="min-w-full text-sm">
        <thead className="bg-slate-900 text-slate-300">
          <tr>
            <th className="px-4 py-2 text-left">Container</th>
            <th className="px-4 py-2 text-left">Namespace</th>
            <th className="px-4 py-2 text-left">Agent</th>
            <th className="px-4 py-2 text-left">Model</th>
            <th className="px-4 py-2 text-left">Last Verdict</th>
            <th className="px-4 py-2 text-left">Last Score</th>
            <th className="px-4 py-2 text-left">Last Deploy</th>
          </tr>
        </thead>
        <tbody>
          {containers.map((c) => (
            <tr
              key={c.id}
              className="border-t border-slate-800 hover:bg-slate-800/60 cursor-pointer"
              onClick={() => onSelect?.(c.id)}
            >
              <td className="px-4 py-2 font-semibold text-slate-100">{c.id}</td>
              <td className="px-4 py-2">{c.namespace}</td>
              <td className="px-4 py-2">{c.agentConnected ? badge('Connected', 'bg-green-500/20 text-green-300') : badge('Disconnected', 'bg-red-500/20 text-red-300')}</td>
              <td className="px-4 py-2">
                {badge(c.modelStatus, c.modelStatus === 'READY' ? 'bg-green-500/20 text-green-200' : 'bg-amber-500/20 text-amber-200')}
              </td>
              <td className="px-4 py-2">
                {c.lastVerdict ? badge(c.lastVerdict, c.lastVerdict === 'THREAT' ? 'bg-red-500/20 text-red-200' : 'bg-green-500/20 text-green-200') : '—'}
              </td>
              <td className="px-4 py-2">{c.lastScore !== undefined ? c.lastScore.toFixed(2) : '—'}</td>
              <td className="px-4 py-2 text-slate-400">{c.lastDeploy ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

