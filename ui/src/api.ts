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
