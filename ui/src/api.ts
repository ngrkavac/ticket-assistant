export type LoggedRequest = {
  requestId: string;
  question: string;
  status: 'SUCCESS' | 'ERROR';
  customer: string | null;
  createdAt: string;
};

export type CatalogQuestion = {
  id: number;
  text: string;
};

export type User = {
  name: string;
  email: string | null;
  roles: string[];
};

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

// oauth2-proxy (K2) owns these paths: it signs the user in with the identity provider and keeps
// the session in a cookie, so no token ever reaches the browser.
export const SIGN_IN_PATH = '/oauth2/sign_in';
export const SIGN_OUT_PATH = '/oauth2/sign_out';
const USER_INFO_PATH = '/oauth2/userinfo';

// If the assistant still answers 401 right after a fresh sign-in, redirecting again would loop
// forever (the identity provider signs the user in silently), so redirect at most once per minute.
const SIGN_IN_GUARD_KEY = 'resolva-sign-in-at';
const SIGN_IN_GUARD_MS = 60_000;

/** Indirection over window.location, so tests can observe redirects (jsdom cannot navigate). */
export const browser = {
  assign: (url: string) => window.location.assign(url),
  now: () => Date.now(),
};

function readGuard(): number {
  try {
    return Number(sessionStorage.getItem(SIGN_IN_GUARD_KEY)) || 0;
  } catch {
    return 0;
  }
}

function writeGuard(value: number) {
  try {
    sessionStorage.setItem(SIGN_IN_GUARD_KEY, String(value));
  } catch {
    // storage unavailable: the loop guard only lasts for this page view
  }
}

/**
 * Sends the browser to the sign-in flow and back to the current page afterwards. Returns false,
 * without navigating, when a sign-in was already started less than a minute ago.
 */
export function redirectToSignIn(): boolean {
  const now = browser.now();
  if (now - readGuard() < SIGN_IN_GUARD_MS) {
    return false;
  }
  writeGuard(now);
  const back = window.location.pathname + window.location.search;
  browser.assign(`${SIGN_IN_PATH}?rd=${encodeURIComponent(back)}`);
  return true;
}

async function send<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    let detail = '';
    try {
      detail = (await res.json()).detail ?? '';
    } catch {
      // body was not problem+json
    }
    if (res.status === 401) {
      detail = redirectToSignIn()
        ? 'Your session has expired. Taking you to sign-in…'
        : 'You are signed in, but the assistant did not accept the session. Try again later.';
    }
    throw new ApiError(res.status, detail || `Request failed (${res.status}).`);
  }
  return res.json() as Promise<T>;
}

type UserInfo = { user?: string; email?: string; preferredUsername?: string; groups?: string[] };

/**
 * The signed-in user from oauth2-proxy's /oauth2/userinfo; the roles come from the token's
 * `roles` claim (K1). Resolves to null when there is no oauth2-proxy in front of the UI (the Vite
 * dev server), and redirects to sign-in when the session is missing or has expired.
 */
export async function getUser(): Promise<User | null> {
  let res: Response;
  try {
    res = await fetch(USER_INFO_PATH, { headers: { Accept: 'application/json' } });
  } catch {
    return null;
  }
  if (res.status === 401) {
    redirectToSignIn();
    return null;
  }
  let info: UserInfo;
  try {
    info = res.ok ? ((await res.json()) as UserInfo) : {};
  } catch {
    return null; // e.g. the dev server's index.html fallback
  }
  const name = info.preferredUsername || info.user || info.email;
  if (!name) {
    return null;
  }
  return { name, email: info.email || null, roles: Array.isArray(info.groups) ? info.groups : [] };
}

function postJson<T>(url: string, body: unknown): Promise<T> {
  return send(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export function ask(question: string, customer: string): Promise<{ answer: string }> {
  return postJson('/ask', { question, customer });
}

export function listRequests(customer: string, limit = 5): Promise<LoggedRequest[]> {
  return send(`/requests?limit=${limit}&customer=${encodeURIComponent(customer)}`);
}

export function listCustomers(): Promise<string[]> {
  return send('/customers');
}

export function listQuestions(): Promise<CatalogQuestion[]> {
  return send('/questions');
}

export function addQuestion(text: string): Promise<CatalogQuestion> {
  return postJson('/questions', { text });
}
