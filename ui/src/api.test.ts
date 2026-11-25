import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, ask, browser, getUser, listRequests } from './api';
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

  describe('when the session is missing or expired (401)', () => {
    let assign: ReturnType<typeof vi.spyOn>;
    let now = 1_000_000;
    beforeEach(() => {
      assign = vi.spyOn(browser, 'assign').mockImplementation(() => {});
      vi.spyOn(browser, 'now').mockImplementation(() => now);
      window.history.replaceState(null, '', '/?tab=history');
    });

    it('redirects to sign-in and back to the current page', async () => {
      mockFetch({ 'POST /ask': () => problem(401, 'Unauthorized') });

      await expect(ask('q', 'acme')).rejects.toMatchObject({
        status: 401,
        message: 'Your session has expired. Taking you to sign-in…',
      });
      expect(assign).toHaveBeenCalledWith('/oauth2/sign_in?rd=%2F%3Ftab%3Dhistory');
    });

    it('does not redirect again within a minute, so a rejected session cannot loop', async () => {
      mockFetch({ 'POST /ask': () => problem(401, 'Unauthorized') });
      await ask('q', 'acme').catch(() => {});

      now += 59_000;
      await expect(ask('q', 'acme')).rejects.toMatchObject({
        status: 401,
        message: 'You are signed in, but the assistant did not accept the session. Try again later.',
      });
      expect(assign).toHaveBeenCalledTimes(1);

      now += 2_000;
      await ask('q', 'acme').catch(() => {});
      expect(assign).toHaveBeenCalledTimes(2);
    });

    it('redirects when the user info needs a sign-in', async () => {
      mockFetch({ 'GET /oauth2/userinfo': () => new Response('Unauthorized', { status: 401 }) });

      await expect(getUser()).resolves.toBeNull();
      expect(assign).toHaveBeenCalledWith('/oauth2/sign_in?rd=%2F%3Ftab%3Dhistory');
    });
  });

  describe('getUser', () => {
    it('maps the oauth2-proxy user info, with the roles from the groups', async () => {
      mockFetch({
        'GET /oauth2/userinfo': () =>
          json({
            user: '0b6f-uuid',
            email: 'acme-agent@helpdesk.local',
            preferredUsername: 'acme-agent',
            groups: ['helpdesk-agent'],
          }),
      });

      await expect(getUser()).resolves.toEqual({
        name: 'acme-agent',
        email: 'acme-agent@helpdesk.local',
        roles: ['helpdesk-agent'],
      });
    });

    it('falls back to the user id and no roles', async () => {
      mockFetch({ 'GET /oauth2/userinfo': () => json({ user: 'someone' }) });

      await expect(getUser()).resolves.toEqual({ name: 'someone', email: null, roles: [] });
    });

    it('is null without oauth2-proxy in front (dev server)', async () => {
      mockFetch({ 'GET /oauth2/userinfo': () => new Response('<!doctype html>', { status: 200 }) });
      await expect(getUser()).resolves.toBeNull();

      mockFetch({ 'GET /oauth2/userinfo': () => new Response('Not found', { status: 404 }) });
      await expect(getUser()).resolves.toBeNull();

      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
      await expect(getUser()).resolves.toBeNull();
    });
  });
});
