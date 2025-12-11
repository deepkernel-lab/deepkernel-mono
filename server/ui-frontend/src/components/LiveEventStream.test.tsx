import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { LiveEventStream } from './LiveEventStream';

describe('LiveEventStream', () => {
  it('renders events newest first', () => {
    const events = [
      { type: 'WINDOW_SCORED', timestamp: 't2', container_id: 'c1', ml_score: -0.5, is_anomalous: false },
      { type: 'TRIAGE_RESULT', timestamp: 't1', container_id: 'c1', verdict: 'SAFE' },
    ];
    render(<LiveEventStream events={events} />);
    const cards = screen.getAllByText(/WINDOW_SCORED|TRIAGE_RESULT/);
    expect(cards[0].textContent).toContain('WINDOW_SCORED');
  });
});

