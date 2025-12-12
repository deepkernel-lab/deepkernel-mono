// @ts-nocheck
import { AnomalyEvent, Container, ModelVersion, ScorePoint } from './types';

const API_BASE = import.meta.env.VITE_API_BASE || 'http://13.204.239.189:9090';

async function fetchText(path: string): Promise<{ ok: boolean; status: number; text: string }> {
  const res = await fetch(API_BASE + path);
  const text = await res.text();
  return { ok: res.ok, status: res.status, text };
}

async function fetchJson<T>(path: string): Promise<T> {
  const { ok, status, text } = await fetchText(path);
  if (!ok) throw new Error(`HTTP ${status}`);
  if (!text) throw new Error('Empty response body');
  return JSON.parse(text) as T;
}

async function fetchJsonArray<T>(path: string): Promise<T[]> {
  const { ok, status, text } = await fetchText(path);
  if (!ok) throw new Error(`HTTP ${status}`);
  if (!text) return [];
  const parsed = JSON.parse(text);
  return Array.isArray(parsed) ? (parsed as T[]) : [];
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(API_BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  // Some endpoints may return empty body; handle safely
  const text = await res.text();
  return (text ? (JSON.parse(text) as T) : (undefined as unknown as T));
}

export async function getContainers(): Promise<Container[]> {
  try {
    return await fetchJsonArray<Container>('/api/ui/containers');
  } catch (e) {
    console.warn('Failed to load containers', e);
    return [];
  }
}

export async function getContainerModels(id: string): Promise<ModelVersion[]> {
  try {
    return await fetchJsonArray<ModelVersion>(`/api/ui/containers/${encodeURIComponent(id)}/models`);
  } catch (e) {
    console.warn('Failed to load models', e);
    return [];
  }
}

export async function triggerTraining(containerId: string): Promise<void> {
  try {
    await postJson<void>(`/api/ui/train/${encodeURIComponent(containerId)}`, null);
  } catch (e) {
    console.warn('Failed to trigger training', e);
  }
}

export async function setDemoChangeContext(params: {
  containerId: string;
  commitId: string;
  repoUrl: string;
  changedFiles: string[];
  diffSummary: string;
}): Promise<void> {
  await postJson<void>('/api/ui/demo/change-context', {
    container_id: params.containerId,
    commit_id: params.commitId,
    repo_url: params.repoUrl,
    changed_files: params.changedFiles,
    diff_summary: params.diffSummary,
  });
}

export type DemoTriageRequest = {
  containerId: string;
  mlScore: number;
  anomalous: boolean;
  syscallSummary: string;
  diffSummary: string;
  commitId?: string;
  repoUrl?: string;
  changedFiles?: string[];
};

export type DemoTriageResponse = {
  id: string;
  containerId: string;
  windowId: string;
  riskScore: number;
  verdict: string;
  explanation: string;
  llmResponseRaw?: string | null;
};

export async function runDemoTriage(req: DemoTriageRequest): Promise<DemoTriageResponse> {
  return await postJson<DemoTriageResponse>('/api/ui/demo/triage', {
    container_id: req.containerId,
    ml_score: req.mlScore,
    is_anomalous: req.anomalous,
    syscall_summary: req.syscallSummary,
    diff_summary: req.diffSummary,
    commit_id: req.commitId ?? 'demo',
    repo_url: req.repoUrl ?? 'demo',
    changed_files: req.changedFiles ?? [],
  });
}

export async function getTriageEnabled(): Promise<boolean> {
  const res = await fetch(API_BASE + '/api/admin/triage/enabled');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const body = await res.json();
  return Boolean(body.enableLlm);
}

export async function setTriageEnabled(enable: boolean): Promise<void> {
  await postJson('/api/admin/triage/enabled', { enableLlm: enable });
}

export async function getContainerScores(id: string, limit = 50): Promise<ScorePoint[]> {
  try {
    const points = await fetchJsonArray<any>(`/api/ui/containers/${encodeURIComponent(id)}/scores?limit=${limit}`);
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
    return await fetchJsonArray(`/api/ui/events?limit=${limit}`);
  } catch (e) {
    console.warn('Failed to load events', e);
    return [];
  }
}

export async function getContainerEvents(id: string, limit = 100) {
  try {
    return await fetchJsonArray(`/api/ui/events/container/${encodeURIComponent(id)}?limit=${limit}`);
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


