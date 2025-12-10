import { AnomalyEvent } from '../types';
import { summarizeEvent } from '../api';

type Props = {
  events: AnomalyEvent[];
};

export function LiveEventStream({ events }: Props) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 h-full">
      <div className="text-sm font-semibold text-slate-200 mb-2">Live Event Stream</div>
      <div className="space-y-2 max-h-[480px] overflow-y-auto text-sm">
        {events.map((evt, idx) => (
          <div key={idx} className="rounded-lg border border-slate-800/80 bg-slate-900/80 p-3">
            <div className="text-xs text-slate-400">
              {evt.timestamp ?? new Date().toISOString()} • {evt.type}
            </div>
            <div className="text-slate-100">{summarizeEvent(evt)}</div>
          </div>
        ))}
        {events.length === 0 && <div className="text-slate-500 text-sm">Waiting for events...</div>}
      </div>
    </div>
  );
}

