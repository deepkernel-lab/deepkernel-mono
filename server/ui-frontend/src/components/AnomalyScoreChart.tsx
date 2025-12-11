import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

type Props = {
  scores: { ts: string; score: number }[];
};

export function AnomalyScoreChart({ scores }: Props) {
  return (
    <div className="h-48 w-full rounded-xl border border-slate-800 bg-slate-900/60 p-3">
      <div className="text-sm font-semibold text-slate-200 mb-2">Anomaly Score (recent)</div>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={scores}>
          <XAxis dataKey="ts" hide />
          <YAxis domain={[-1, 1]} tick={{ fill: '#94a3b8', fontSize: 12 }} />
          <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #1e293b' }} />
          <Line type="monotone" dataKey="score" stroke="#38bdf8" dot={false} strokeWidth={2} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

