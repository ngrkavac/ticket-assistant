export type LoggedRequest = {
  requestId: string;
  question: string;
  status: 'SUCCESS' | 'ERROR';
  createdAt: string;
};

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
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
      detail = 'Unauthorized: HELPDESK_API_KEY is not set for the UI server.';
    }
    throw new ApiError(res.status, detail || `Request failed (${res.status}).`);
  }
  return res.json() as Promise<T>;
}

export function ask(question: string): Promise<{ answer: string }> {
  return send('/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
  });
}

export function listRequests(limit = 20): Promise<LoggedRequest[]> {
  return send(`/requests?limit=${limit}`);
}
