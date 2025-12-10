import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { VerdictCard } from './VerdictCard';

describe('VerdictCard', () => {
  it('shows verdict and score', () => {
    render(<VerdictCard verdict="THREAT" score={0.9} explanation="test" />);
    expect(screen.getByText('THREAT')).toBeInTheDocument();
    expect(screen.getByText(/Score/)).toHaveTextContent('0.90');
    expect(screen.getByText('test')).toBeInTheDocument();
  });
});

