import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';
import * as api from '../api';
import { MemoryRouter } from 'react-router-dom';

describe('DashboardPage', () => {
  it('loads and renders containers', async () => {
    vi.spyOn(api, 'getContainers').mockResolvedValue([
      {
        id: 'c1',
        namespace: 'ns',
        node: 'n1',
        status: 'Running',
        agentConnected: true,
        modelStatus: 'READY',
        lastVerdict: 'SAFE',
        lastScore: -0.2,
      },
    ]);

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>
    );

    expect(await screen.findByText('c1')).toBeInTheDocument();
  });
});

