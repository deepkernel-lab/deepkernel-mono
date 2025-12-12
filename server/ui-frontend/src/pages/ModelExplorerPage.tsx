// @ts-nocheck
import React, { useEffect, useState } from 'react';
import { getContainers, getContainerModels, triggerTraining } from '../api';
import { Container, ModelVersion } from '../types';
import { ModelVersionCard } from '../components/ModelVersionCard';

export function ModelExplorerPage() {
  const [containers, setContainers] = useState<Container[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [models, setModels] = useState<ModelVersion[]>([]);
  const [isTraining, setIsTraining] = useState(false);

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

  const refreshModels = async (id: string) => {
    const list = await getContainerModels(id);
    setModels(list);
    return list;
  };

  const pollUntilReady = async (id: string, attempt = 0) => {
    const list = await refreshModels(id);
    const hasReady = list.some((m) => m.status === 'READY');
    const hasTraining = list.some((m) => m.status === 'TRAINING');
    if (hasReady || attempt >= 30) {
      setIsTraining(false);
      return;
    }
    if (hasTraining) {
      setTimeout(() => pollUntilReady(id, attempt + 1), 2000);
    } else {
      // No training entry found; stop spinner
      setIsTraining(false);
    }
  };

  const startTraining = async (id: string) => {
    setIsTraining(true);
    try {
      await triggerTraining(id);
      await pollUntilReady(id, 0);
    } catch (e) {
      setIsTraining(false);
    }
  };

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
              disabled={isTraining}
              onClick={async () => {
                if (selected) {
                  await startTraining(selected);
                }
              }}
            >
              {isTraining ? (
                <span className="inline-flex items-center gap-2">
                  <span className="h-3 w-3 animate-spin rounded-full border-2 border-sky-300 border-t-transparent" />
                  Creating model...
                </span>
              ) : (
                'Create model (trigger training)'
              )}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

