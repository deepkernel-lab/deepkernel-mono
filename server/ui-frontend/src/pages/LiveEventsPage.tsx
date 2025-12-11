import { useEffect, useState } from 'react';
import { LiveEventStream } from '../components/LiveEventStream';
import { useWebsocketEvents } from '../hooks/useWebsocketEvents';
import { getEvents } from '../api';

export function LiveEventsPage() {
  const wsEvents = useWebsocketEvents();
  const [history, setHistory] = useState<any[]>([]);

  useEffect(() => {
    getEvents(50).then(setHistory);
  }, []);

  const merged = [...wsEvents, ...history].slice(0, 200);
  return (
    <div>
      <LiveEventStream events={merged} />
    </div>
  );
}

