import { useEffect, useState } from 'react';
import { getContainers, getContainerModels } from '../api';
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
    </div>
  );
}

