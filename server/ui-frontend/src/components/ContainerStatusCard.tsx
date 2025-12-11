import { Container } from '../types';

type Props = {
  container: Container;
};

export function ContainerStatusCard({ container }: Props) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm text-slate-400">Container</div>
          <div className="text-lg font-semibold text-slate-50">{container.id}</div>
          <div className="text-sm text-slate-400">
            Namespace: {container.namespace} • Node: {container.node}
          </div>
        </div>
        <div className="text-right">
          <div className="text-sm text-slate-400">Model</div>
          <div className="text-base font-semibold">{container.modelStatus}</div>
          <div className="text-sm text-slate-400">Last verdict: {container.lastVerdict ?? '—'}</div>
        </div>
      </div>
    </div>
  );
}

