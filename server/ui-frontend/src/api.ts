import { AnomalyEvent, Container, ModelVersion } from './types';

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
    return `${cid} → score=${evt.ml_score} anomalous=${evt.is_anomalous}`;
  }
  return JSON.stringify(evt);
}


