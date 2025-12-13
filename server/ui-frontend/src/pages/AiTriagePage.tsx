// @ts-nocheck
import { useEffect, useMemo, useState } from 'react';
import { getContainers, runDemoTriage, setDemoChangeContext, getTriageEnabled, setTriageEnabled, type DemoTriageResponse } from '../api';
import type { Container } from '../types';

const DEFAULT_SYSCALL_SUMMARY =
  'record_count=120\narg_class_counts=FILE=10 NET=80 PROC=10 MEM=0 OTHER=20\ntop_syscalls=connect:55, socket:20, open:10\nsequence_sample=socket → connect → connect';

export function AiTriagePage() {
  const [containers, setContainers] = useState<Container[]>([]);
  const [containerId, setContainerId] = useState<string>('');
  const [commitId, setCommitId] = useState('demo-commit');
  const [repoUrl, setRepoUrl] = useState('demo');
  const [changedFiles, setChangedFiles] = useState('demo-apps/bachat-bank/backend/main.py');
  const [diffSummary, setDiffSummary] = useState('');
  const [mlScore, setMlScore] = useState(0.95);
  const [anomalous, setAnomalous] = useState(true);
  const [syscallSummary, setSyscallSummary] = useState(DEFAULT_SYSCALL_SUMMARY);
  const [result, setResult] = useState<DemoTriageResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [llmEnabled, setLlmEnabled] = useState(false);
  const filesList = useMemo(
    () => changedFiles.split(',').map((s) => s.trim()).filter(Boolean),
    [changedFiles],
  );

  useEffect(() => {
    getContainers().then((list) => {
      setContainers(list);
      setContainerId((prev) => (prev ? prev : list.length > 0 ? list[0].id : ''));
    });
    getTriageEnabled().then(setLlmEnabled).catch(() => setLlmEnabled(false));
  }, []);

  async function onSetContext() {
    if (!containerId) return;
    setBusy(true);
    try {
      await setDemoChangeContext({
        containerId,
        commitId,
        repoUrl,
        changedFiles: filesList,
        diffSummary,
      });
    } finally {
      setBusy(false);
    }
  }

  async function onRunTriage() {
    if (!containerId) return;
    setBusy(true);
    try {
      const res = await runDemoTriage({
        containerId,
        mlScore,
        anomalous,
        syscallSummary,
        diffSummary,
        commitId,
        repoUrl,
        changedFiles: filesList,
      });
      setResult(res);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4 max-w-4xl">
      <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
        <div className="text-sm font-semibold text-slate-200 mb-3">AI Triage (Gemini)</div>

        <div className="flex items-center gap-3 mb-4">
          <label className="flex items-center gap-2 text-sm text-slate-200">
            <input
              type="checkbox"
              checked={llmEnabled}
              onChange={async (e) => {
                const v = e.target.checked;
                setLlmEnabled(v);
                try {
                  await setTriageEnabled(v);
                } catch {
                  // revert on failure
                  setLlmEnabled(!v);
                }
              }}
            />
            Enable AI Triage
          </label>
          <span className="text-xs text-amber-300">
            When enabled, triage uses LLM inference; otherwise heuristic only.
          </span>
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <div className="text-xs text-slate-400 mb-1">Container</div>
            <select
              className="w-full rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
              value={containerId}
              onChange={(e) => setContainerId(e.target.value)}
            >
              {containers.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.id}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <div className="text-xs text-slate-400 mb-1">ML score</div>
              <input
                className="w-full rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
                type="number"
                step="0.01"
                value={mlScore}
                onChange={(e) => setMlScore(Number(e.target.value))}
              />
            </div>
            <div className="flex items-end gap-2">
              <label className="flex items-center gap-2 text-sm text-slate-200">
                <input
                  type="checkbox"
                  checked={anomalous}
                  onChange={(e) => setAnomalous(e.target.checked)}
                />
                anomalous
              </label>
            </div>
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-2 mt-3">
          <div>
            <div className="text-xs text-slate-400 mb-1">Commit ID</div>
            <input
              className="w-full rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
              value={commitId}
              onChange={(e) => setCommitId(e.target.value)}
            />
          </div>
          <div>
            <div className="text-xs text-slate-400 mb-1">Repo URL</div>
            <input
              className="w-full rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
            />
          </div>
        </div>

        <div className="mt-3">
          <div className="text-xs text-slate-400 mb-1">Changed files (comma-separated)</div>
          <input
            className="w-full rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
            value={changedFiles}
            onChange={(e) => setChangedFiles(e.target.value)}
          />
        </div>

        <div className="mt-3">
          <div className="text-xs text-slate-400 mb-1">Diff summary / change context</div>
          <textarea
            className="w-full h-28 rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
            value={diffSummary}
            onChange={(e) => setDiffSummary(e.target.value)}
            placeholder="Paste a short change description or diff snippet here..."
          />
        </div>

        <div className="mt-3">
          <div className="text-xs text-slate-400 mb-1">Syscall window summary</div>
          <textarea
            className="w-full h-28 rounded-md bg-slate-950 border border-slate-800 px-3 py-2 text-slate-200 text-sm"
            value={syscallSummary}
            onChange={(e) => setSyscallSummary(e.target.value)}
          />
        </div>

        <div className="mt-4 flex gap-2">
          <button
            className="rounded-md border border-slate-700 px-3 py-2 text-sm text-slate-200 hover:bg-slate-800/50 disabled:opacity-50"
            onClick={onSetContext}
            disabled={busy || !containerId}
          >
            Set change context
          </button>
          <button
            className="rounded-md bg-sky-500/20 text-sky-200 border border-sky-500/40 px-3 py-2 text-sm hover:bg-sky-500/30 disabled:opacity-50"
            onClick={onRunTriage}
            disabled={busy || !containerId}
          >
            Run AI triage
          </button>
        </div>
      </div>

      <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4">
        <div className="text-sm font-semibold text-slate-200 mb-2">Result</div>
        {!result ? (
          <div className="text-sm text-slate-400">Run triage to see verdict + explanation. A TRIAGE_RESULT event will also appear in Live Events.</div>
        ) : (
          <div className="space-y-2">
            <div className="text-sm text-slate-200">
              <span className="text-slate-400">Verdict:</span> {result.verdict}{' '}
              <span className="text-slate-400 ml-3">Risk:</span> {result.riskScore}
            </div>
            <div className="text-sm text-slate-200 whitespace-pre-wrap">{result.explanation}</div>
            {result.llmResponseRaw ? (
              <details className="text-sm text-slate-300">
                <summary className="cursor-pointer text-slate-300">Raw LLM JSON</summary>
                <pre className="mt-2 whitespace-pre-wrap text-xs text-slate-300">{result.llmResponseRaw}</pre>
              </details>
            ) : null}
          </div>
        )}
      </div>
    </div>
  );
}


