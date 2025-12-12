export type Container = {
  id: string;
  namespace: string;
  node: string;
  status: string;
  agentConnected: boolean;
  modelStatus: 'UNTRAINED' | 'READY' | 'TRAINING' | 'ERROR';
  lastVerdict?: 'SAFE' | 'THREAT' | 'UNKNOWN';
  lastScore?: number;
  lastExplanation?: string;
  lastDeploy?: string;
  policyStatus?: string;
  policyType?: string;
};

export type AnomalyEvent = {
  type: string;
  timestamp?: string;
  container_id?: string;
  containerId?: string;
  payload?: Record<string, unknown>;
  ml_score?: number;
  is_anomalous?: boolean;
  [key: string]: unknown;
};

export type ScorePoint = {
  ts: string;
  score: number;
  anomalous: boolean;
};

export type ModelVersion = {
  version: number;
  trainedAt: string;
  featureVersion: string;
  status: string;
};

