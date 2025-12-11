import { Container } from '../types';

type Props = {
  containers: Container[];
};

export function KpiCards({ containers }: Props) {
  const monitored = containers.length;
  const safe = containers.filter((c) => c.lastVerdict === 'SAFE').length;
  const threat = containers.filter((c) => c.lastVerdict === 'THREAT').length;
  const ready = containers.filter((c) => c.modelStatus === 'READY').length;

  const items = [
    { label: 'Monitored Containers', value: monitored },
    { label: 'SAFE (last hour)', value: safe },
    { label: 'THREAT (last hour)', value: threat },
    { label: 'Models READY', value: ready },
  ];

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {items.map((item) => (
        <div key={item.label} className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
          <div className="text-xs uppercase tracking-wide text-slate-400">{item.label}</div>
          <div className="text-2xl font-semibold text-slate-50 mt-1">{item.value}</div>
        </div>
      ))}
    </div>
  );
}

