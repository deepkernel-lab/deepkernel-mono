import { ModelVersion } from '../types';

type Props = {
  model: ModelVersion;
};

export function ModelVersionCard({ model }: Props) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
      <div className="text-sm text-slate-400">Version {model.version}</div>
      <div className="text-lg font-semibold text-slate-50">Feature {model.featureVersion}</div>
      <div className="text-sm text-slate-400">Status: {model.status}</div>
      <div className="text-sm text-slate-400">Trained: {model.trainedAt}</div>
    </div>
  );
}

