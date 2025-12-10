type Props = {
  verdict: 'SAFE' | 'THREAT' | 'UNKNOWN';
  score?: number;
  explanation?: string;
};

export function VerdictCard({ verdict, score, explanation }: Props) {
  const color =
    verdict === 'THREAT'
      ? 'bg-red-500/20 text-red-200 border-red-500/30'
      : verdict === 'SAFE'
        ? 'bg-green-500/20 text-green-200 border-green-500/30'
        : 'bg-slate-800 text-slate-200 border-slate-700';
  return (
    <div className={`rounded-xl border p-4 ${color}`}>
      <div className="text-sm uppercase tracking-wide">Latest Verdict</div>
      <div className="text-2xl font-semibold mt-1">{verdict}</div>
      {score !== undefined && <div className="text-sm text-slate-200/80">Score: {score.toFixed(2)}</div>}
      {explanation && <p className="mt-2 text-sm text-slate-100">{explanation}</p>}
    </div>
  );
}

