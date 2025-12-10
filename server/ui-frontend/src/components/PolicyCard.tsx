type Props = {
  policyType?: string;
  status?: string;
  appliedAt?: string;
  node?: string;
  details?: string;
};

export function PolicyCard({ policyType, status, appliedAt, node, details }: Props) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
      <div className="text-sm text-slate-400">Policy</div>
      <div className="text-lg font-semibold text-slate-50">{policyType ?? '—'}</div>
      <div className="text-sm text-slate-400">Status: {status ?? '—'}</div>
      <div className="text-sm text-slate-400">Node: {node ?? '—'}</div>
      <div className="text-sm text-slate-400">Applied: {appliedAt ?? '—'}</div>
      {details && <div className="mt-2 text-sm text-slate-200">{details}</div>}
    </div>
  );
}

