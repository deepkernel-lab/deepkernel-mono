import { useEffect, useState } from 'react';
import { getContainers, getContainerModels, triggerTraining } from '../api';
import { Container, ModelVersion } from '../types';
import { ModelVersionCard } from '../components/ModelVersionCard';

export function ModelExplorerPage() {
  const [containers, setContainers] = useState<Container[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [models, setModels] = useState<ModelVersion[]>([]);

  useEffect(() => {
    getContainers().then((list) => {
      setContainers(list);
      if (list.length > 0) {
        setSelected(list[0].id);
      }
    });
  }, []);

  useEffect(() => {
    if (selected) {
      getContainerModels(selected).then(setModels);
    }
  }, [selected]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        {containers.map((c) => (
          <button
            key={c.id}
            onClick={() => setSelected(c.id)}
            className={`rounded-full border px-3 py-1 text-sm ${
              selected === c.id ? 'border-sky-400 text-sky-200' : 'border-slate-700 text-slate-300'
            }`}
          >
            {c.id}
          </button>
        ))}
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {models.map((m) => (
          <ModelVersionCard key={m.version} model={m} />
        ))}
      </div>
      {selected && models.length === 0 && (
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 text-slate-200">
          <div className="text-sm font-semibold mb-1">No models found for this container</div>
          <div className="text-sm text-slate-400">
            This is expected until training runs at least once. For demo, you can create a placeholder model entry.
          </div>
          <div className="mt-3">
            <button
              className="rounded-md bg-sky-500/20 text-sky-200 border border-sky-500/40 px-3 py-2 text-sm hover:bg-sky-500/30"
              onClick={async () => {
                await triggerTraining(selected);
                const list = await getContainerModels(selected);
                setModels(list);
              }}
            >
              Create model (trigger training)
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

