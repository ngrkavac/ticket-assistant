import { describe, expect, it } from 'vitest';
import { ApiError, ask, listRequests } from './api';
import { json, mockFetch, problem } from './test/fetchMock';

describe('api', () => {
  it('posts the question and customer to /ask and returns the answer', async () => {
    const fetchMock = mockFetch({ 'POST /ask': () => json({ answer: 'Hi' }) });

    await expect(ask('Open tickets?', 'acme')).resolves.toEqual({ answer: 'Hi' });
    const [, init] = fetchMock.mock.calls[0];
    expect(JSON.parse(String(init?.body))).toEqual({ question: 'Open tickets?', customer: 'acme' });
  });

  it('encodes the customer in the history query', async () => {
    const fetchMock = mockFetch({ 'GET /requests': () => json([]) });

    await listRequests('a&b', 3);
    expect(fetchMock.mock.calls[0][0]).toBe('/requests?limit=3&customer=a%26b');
  });

  it('uses the problem detail as the error message', async () => {
    mockFetch({ 'POST /ask': () => problem(503, 'The assistant is busy, try again.') });

    const error = await ask('q', 'acme').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 503, message: 'The assistant is busy, try again.' });
  });

  it('falls back to a generic message when the body is not JSON', async () => {
    mockFetch({ 'POST /ask': () => new Response('<html>Bad gateway</html>', { status: 502 }) });

    await expect(ask('q', 'acme')).rejects.toMatchObject({ status: 502, message: 'Request failed (502).' });
  });

  it('explains a 401', async () => {
    mockFetch({ 'POST /ask': () => problem(401, 'Missing API key') });

    await expect(ask('q', 'acme')).rejects.toMatchObject({
      status: 401,
      message: 'Unauthorized: HELPDESK_API_KEY is not set for the UI server.',
    });
  });
});
