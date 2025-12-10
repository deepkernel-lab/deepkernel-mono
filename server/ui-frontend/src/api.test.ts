import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { getContainers, getContainerModels, getEvents } from './api';

const mockResponse = (data: unknown) =>
  Promise.resolve({
    ok: true,
    json: () => Promise.resolve(data),
  } as Response);

describe('api.ts', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.resetAllMocks();
  });

  it('fetches containers', async () => {
    (global.fetch as any).mockReturnValueOnce(mockResponse([{ id: 'c1' }]));
    const res = await getContainers();
    expect(res[0].id).toBe('c1');
    expect(global.fetch).toHaveBeenCalledWith('/api/ui/containers');
  });

  it('fetches models', async () => {
    (global.fetch as any).mockReturnValueOnce(mockResponse([{ version: 1 }]));
    const res = await getContainerModels('c1');
    expect(res[0].version).toBe(1);
    expect(global.fetch).toHaveBeenCalledWith('/api/ui/containers/c1/models');
  });

  it('fetches events', async () => {
    (global.fetch as any).mockReturnValueOnce(mockResponse([{ type: 'WINDOW_SCORED' }]));
    const res = await getEvents(5);
    expect(res[0].type).toBe('WINDOW_SCORED');
    expect(global.fetch).toHaveBeenCalledWith('/api/ui/events?limit=5');
  });
});

