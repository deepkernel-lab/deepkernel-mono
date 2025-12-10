import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getContainers, getContainerModels } from '../api';
import { Container, ModelVersion } from '../types';
import { ContainerStatusCard } from '../components/ContainerStatusCard';
import { AnomalyScoreChart } from '../components/AnomalyScoreChart';
import { VerdictCard } from '../components/VerdictCard';
import { PolicyCard } from '../components/PolicyCard';
import { ModelVersionCard } from '../components/ModelVersionCard';

export function ContainerPage() {
  const { id } = useParams<{ id: string }>();
  const [container, setContainer] = useState<Container | null>(null);
  const [models, setModels] = useState<ModelVersion[]>([]);

  useEffect(() => {
    getContainers().then((list) => setContainer(list.find((c) => c.id === id) ?? null));
    if (id) {
      getContainerModels(id).then(setModels);
    }
  }, [id]);

  const chartData = useMemo(() => {
    const base = container?.lastScore ?? -0.2;
    return [
      { ts: 't-3', score: base - 0.1 },
      { ts: 't-2', score: base - 0.05 },
      { ts: 't-1', score: base },
    ];
  }, [container?.lastScore]);

  if (!container) {
    return <div className="text-slate-300">Loading container...</div>;
  }

  return (
    <div className="space-y-4">
      <ContainerStatusCard container={container} />
      <div className="grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <AnomalyScoreChart scores={chartData} />
        </div>
        <VerdictCard verdict={(container.lastVerdict as any) ?? 'UNKNOWN'} score={container.lastScore} explanation="LLM explanation will appear here." />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <PolicyCard policyType="SECCOMP" status="PENDING" node={container.node} appliedAt="—" details="deny connect to high ports" />
        <div className="lg:col-span-2 rounded-xl border border-slate-800 bg-slate-900/60 p-4">
          <div className="text-sm font-semibold text-slate-200 mb-2">Model Versions</div>
          <div className="grid gap-3 sm:grid-cols-2">
            {models.map((m) => (
              <ModelVersionCard key={m.version} model={m} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

