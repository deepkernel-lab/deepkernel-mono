import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ContainerTable } from './ContainerTable';

const containers = [
  {
    id: 'prod/billing-api',
    namespace: 'prod',
    node: 'node1',
    status: 'Running',
    agentConnected: true,
    modelStatus: 'READY',
    lastVerdict: 'SAFE',
    lastScore: -0.4,
  },
];

describe('ContainerTable', () => {
  it('renders rows and badges', () => {
    render(<ContainerTable containers={containers} />);
    expect(screen.getByText('prod/billing-api')).toBeInTheDocument();
    expect(screen.getByText('READY')).toBeInTheDocument();
    expect(screen.getByText('SAFE')).toBeInTheDocument();
  });

  it('invokes onSelect when row clicked', () => {
    const onSelect = vi.fn();
    render(<ContainerTable containers={containers} onSelect={onSelect} />);
    fireEvent.click(screen.getByText('prod/billing-api'));
    expect(onSelect).toHaveBeenCalledWith('prod/billing-api');
  });
});

