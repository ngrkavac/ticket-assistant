import { vi } from 'vitest';

export type Handler = (body: unknown, url: URL) => Response | Promise<Response>;

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
}

export function problem(status: number, detail: string): Response {
  return new Response(JSON.stringify({ detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/**
 * Replaces the global fetch with a router keyed by "METHOD /path" (query string ignored).
 * Unknown routes fail the test with a 500 that names the route.
 */
export function mockFetch(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    const key = `${init?.method ?? 'GET'} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) {
      return problem(500, `No mock for ${key}`);
    }
    return handler(init?.body ? JSON.parse(String(init.body)) : undefined, url);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
