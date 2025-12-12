import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getContainers, getContainerModels, getContainerScores } from '../api';
import { Container, ModelVersion, ScorePoint } from '../types';
import { ContainerStatusCard } from '../components/ContainerStatusCard';
import { AnomalyScoreChart } from '../components/AnomalyScoreChart';
import { VerdictCard } from '../components/VerdictCard';
import { PolicyCard } from '../components/PolicyCard';
import { ModelVersionCard } from '../components/ModelVersionCard';
import { useWebsocketEvents } from '../hooks/useWebsocketEvents';

export function ContainerPage() {
  const { id } = useParams<{ id: string }>();
  const [container, setContainer] = useState<Container | null>(null);
  const [models, setModels] = useState<ModelVersion[]>([]);
  const [scores, setScores] = useState<ScorePoint[]>([]);
  const wsEvents = useWebsocketEvents();

  useEffect(() => {
    getContainers().then((list) => setContainer(list.find((c) => c.id === id) ?? null));
    if (id) {
      getContainerModels(id).then(setModels);
      getContainerScores(id, 60).then(setScores);
    }
  }, [id]);

  // Append live scores from WebSocket events (WINDOW_SCORED)
  useEffect(() => {
    if (!id || wsEvents.length === 0) return;
    const evt: any = wsEvents[0];
    if (evt?.type !== 'WINDOW_SCORED') return;
    const cid = evt.containerId ?? evt.container_id;
    if (cid !== id) return;
    const payload = evt.payload ?? evt;
    const ts = String(evt.timestamp ?? new Date().toISOString());
    const score = Number(payload.ml_score ?? payload.score ?? 0);
    const anomalous = Boolean(payload.is_anomalous ?? payload.anomalous ?? false);
    setScores((prev) => {
      const next = [...prev, { ts, score, anomalous }];
      return next.slice(Math.max(next.length - 60, 0));
    });
  }, [id, wsEvents]);

  const chartData = useMemo(() => scores.map((p) => ({ ts: p.ts, score: p.score })), [scores]);

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
        <VerdictCard
          verdict={(container.lastVerdict as any) ?? 'UNKNOWN'}
          score={container.lastScore}
          explanation={container.lastExplanation ?? '—'}
        />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <PolicyCard
          policyType={container.policyType ?? '—'}
          status={container.policyStatus ?? '—'}
          node={container.node}
          appliedAt="—"
          details="Latest policy from policy engine"
        />
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

