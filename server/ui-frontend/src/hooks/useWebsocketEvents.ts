import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { AnomalyEvent } from '../types';

export function useWebsocketEvents() {
  const [events, setEvents] = useState<AnomalyEvent[]>([]);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/events'),
      reconnectDelay: 5000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
    });

    client.onConnect = () => {
      client.subscribe('/topic/events', (msg) => {
        try {
          const body = JSON.parse(msg.body) as AnomalyEvent;
          setEvents((prev) => [body, ...prev].slice(0, 200));
        } catch (e) {
          console.warn('Failed to parse event', e);
        }
      });
    };

    client.activate();
    clientRef.current = client;
    return () => {
      client.deactivate();
    };
  }, []);

  return events;
}

