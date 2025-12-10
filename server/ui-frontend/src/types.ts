export type Container = {
  id: string;
  namespace: string;
  node: string;
  status: string;
  agentConnected: boolean;
  modelStatus: 'UNTRAINED' | 'READY' | 'TRAINING' | 'ERROR';
  lastVerdict?: 'SAFE' | 'THREAT' | 'UNKNOWN';
  lastScore?: number;
  lastDeploy?: string;
};

export type AnomalyEvent = {
  type: string;
  timestamp?: string;
  container_id?: string;
  ml_score?: number;
  is_anomalous?: boolean;
  [key: string]: unknown;
};

export type ModelVersion = {
  version: number;
  trainedAt: string;
  featureVersion: string;
  status: string;
};

