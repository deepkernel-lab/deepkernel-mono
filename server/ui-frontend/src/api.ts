import { AnomalyEvent, Container, ModelVersion } from './types';

const API_BASE = import.meta.env.VITE_API_BASE || '';

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

export function summarizeEvent(evt: AnomalyEvent): string {
  if (evt.type === 'WINDOW_SCORED') {
    return `${evt.container_id} → score=${evt.ml_score} anomalous=${evt.is_anomalous}`;
  }
  return JSON.stringify(evt);
}


