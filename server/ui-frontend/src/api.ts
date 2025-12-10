import { AnomalyEvent, Container, ModelVersion } from './types';

const API_BASE = import.meta.env.VITE_API_BASE || '';

async function fetchJson<T>(path: string, fallback: T): Promise<T> {
  try {
    const res = await fetch(API_BASE + path);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return (await res.json()) as T;
  } catch (e) {
    console.warn(`fetch ${path} failed, using fallback:`, e);
    return fallback;
  }
}

export function getContainers(): Promise<Container[]> {
  return fetchJson<Container[]>('/api/ui/containers', [
    {
      id: 'prod/billing-api',
      namespace: 'prod',
      node: 'worker-01',
      status: 'Running',
      agentConnected: true,
      modelStatus: 'READY',
      lastVerdict: 'SAFE',
      lastScore: -0.4,
      lastDeploy: '2025-12-09T12:04:00Z',
    },
    {
      id: 'prod/payments-svc',
      namespace: 'prod',
      node: 'worker-02',
      status: 'Running',
      agentConnected: true,
      modelStatus: 'READY',
      lastVerdict: 'THREAT',
      lastScore: 0.92,
      lastDeploy: '2025-12-09T12:03:30Z',
    },
  ]);
}

export function getContainerModels(_id: string): Promise<ModelVersion[]> {
  return fetchJson<ModelVersion[]>(`/api/ui/containers/${_id}/models`, [
    { version: 1, trainedAt: new Date().toISOString(), featureVersion: 'v1', status: 'READY' },
  ]);
}

export function summarizeEvent(evt: AnomalyEvent): string {
  if (evt.type === 'WINDOW_SCORED') {
    return `${evt.container_id} → score=${evt.ml_score} anomalous=${evt.is_anomalous}`;
  }
  return JSON.stringify(evt);
}

