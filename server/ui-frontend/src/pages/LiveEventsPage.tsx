import { LiveEventStream } from '../components/LiveEventStream';
import { useWebsocketEvents } from '../hooks/useWebsocketEvents';

export function LiveEventsPage() {
  const events = useWebsocketEvents();
  return (
    <div>
      <LiveEventStream events={events} />
    </div>
  );
}

