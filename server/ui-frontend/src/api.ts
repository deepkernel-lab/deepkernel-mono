import { AnomalyEvent, Container, ModelVersion, ScorePoint } from './types';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://13.204.239.189:9090';

async function fetchJson<T>(path: string): Promise<T> {
  const res = await fetch(API_BASE + path);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as T;
}

export async function getContainers(): Promise<Container[]> {
  try {
    return await fetchJson<Container[]>('/api/ui/containers');
  } catch (e) {
    console.warn('Failed to load containers', e);
    return [];
  }
}

export async function getContainerModels(id: string): Promise<ModelVersion[]> {
  try {
    return await fetchJson<ModelVersion[]>(`/api/ui/containers/${encodeURIComponent(id)}/models`);
  } catch (e) {
    console.warn('Failed to load models', e);
    return [];
  }
}

export async function getContainerScores(id: string, limit = 50): Promise<ScorePoint[]> {
  try {
    const points = await fetchJson<any[]>(`/api/ui/containers/${encodeURIComponent(id)}/scores?limit=${limit}`);
    // server returns {ts: ISO, score: number, anomalous: boolean}
    return points.map((p) => ({
      ts: String(p.ts),
      score: Number(p.score),
      anomalous: Boolean(p.anomalous),
    }));
  } catch (e) {
    console.warn('Failed to load scores', e);
    return [];
  }
}

export async function getEvents(limit = 100) {
  try {
    return await fetchJson(`/api/ui/events?limit=${limit}`);
  } catch (e) {
    console.warn('Failed to load events', e);
    return [];
  }
}

export async function getContainerEvents(id: string, limit = 100) {
  try {
    return await fetchJson(`/api/ui/events/container/${encodeURIComponent(id)}?limit=${limit}`);
  } catch (e) {
    console.warn('Failed to load container events', e);
    return [];
  }
}

export function summarizeEvent(evt: AnomalyEvent): string {
  if (evt.type === 'WINDOW_SCORED') {
    const cid = (evt as any).containerId || evt.container_id;
    const payload = (evt as any).payload || evt;
    return `${cid} → score=${payload.ml_score} anomalous=${payload.is_anomalous}`;
  }
  return JSON.stringify(evt);
}


