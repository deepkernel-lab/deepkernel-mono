import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { LiveEventsPage } from './LiveEventsPage';
import * as api from '../api';
import * as hook from '../hooks/useWebsocketEvents';

describe('LiveEventsPage', () => {
  it('merges history and websocket events', async () => {
    vi.spyOn(api, 'getEvents').mockResolvedValue([
      { type: 'WINDOW_SCORED', timestamp: 't1', container_id: 'c1' },
    ]);
    vi.spyOn(hook, 'useWebsocketEvents').mockReturnValue([{ type: 'TRIAGE_RESULT', timestamp: 't2', container_id: 'c1' }]);

    render(<LiveEventsPage />);
    expect(await screen.findByText(/WINDOW_SCORED/)).toBeInTheDocument();
    expect(screen.getByText(/TRIAGE_RESULT/)).toBeInTheDocument();
  });
});

