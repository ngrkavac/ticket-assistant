import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import { browser, type LoggedRequest } from './api';
import { json, mockFetch, problem, type Handler } from './test/fetchMock';

const QUESTIONS = [
  { id: 1, text: 'Which tickets are open?' },
  { id: 2, text: 'Any outages this week?' },
];

function routes(overrides: Record<string, Handler> = {}): Record<string, Handler> {
  return {
    'GET /customers': () => json(['acme', 'globex']),
    'GET /questions': () => json(QUESTIONS),
    'GET /requests': () => json([]),
    'GET /oauth2/userinfo': () =>
      json({ user: 'u1', email: 'admin@helpdesk.local', preferredUsername: 'admin', groups: ['helpdesk-admin'] }),
    ...overrides,
  };
}

async function selectAndAsk(...questions: string[]) {
  const user = userEvent.setup();
  for (const q of questions) {
    await user.click(await screen.findByRole('checkbox', { name: q }));
  }
  await user.click(screen.getByRole('button', { name: /^Add( \d+)?$/ }));
}

function answers() {
  return screen.getByRole('region', { name: /^Answers/ });
}

describe('App', () => {
  it('asks the selected question for the chosen customer and shows the answer', async () => {
    const logged: LoggedRequest[] = [];
    const fetchMock = mockFetch(
      routes({
        'POST /ask': (body) => {
          const { question, customer } = body as { question: string; customer: string };
          logged.push({ requestId: 'r1', question, status: 'SUCCESS', customer, createdAt: '2026-11-20T10:00:00Z' });
          return json({ answer: `Two tickets are open for ${customer}.` });
        },
        'GET /requests': () => json(logged),
      }),
    );
    render(<App />);
    expect(await screen.findByRole('option', { name: 'acme' })).toBeInTheDocument();

    await selectAndAsk('Which tickets are open?');

    const item = await within(answers()).findByText('Two tickets are open for acme.');
    expect(item.closest('li')).toHaveTextContent('Answered');
    expect(within(answers()).getByRole('heading', { name: 'Answers for acme' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('1 question answered and added to the history.');
    expect(fetchMock).toHaveBeenCalledWith('/ask', expect.objectContaining({ method: 'POST' }));
    // The history is re-read once the assistant has logged the request.
    const history = screen.getByRole('table', { name: 'Asked questions' });
    expect(await within(history).findByText('Which tickets are open?', {}, { timeout: 2000 })).toBeInTheDocument();
    // The checkbox selection is cleared after the batch.
    expect(screen.getByRole('checkbox', { name: 'Which tickets are open?' })).not.toBeChecked();
  });

  it('shows the error of a failed question and still answers the others', async () => {
    mockFetch(
      routes({
        'POST /ask': (body) =>
          (body as { question: string }).question === QUESTIONS[0].text
            ? problem(503, 'The assistant is busy, try again.')
            : json({ answer: 'No outages.' }),
      }),
    );
    render(<App />);
    await screen.findByRole('option', { name: 'acme' });

    await selectAndAsk(QUESTIONS[0].text, QUESTIONS[1].text);

    expect(await within(answers()).findByText('No outages.')).toBeInTheDocument();
    const [failed, answered] = within(answers()).getAllByRole('listitem');
    expect(failed).toHaveTextContent('Failed');
    expect(within(failed).getByRole('alert')).toHaveTextContent('The assistant is busy, try again.');
    expect(answered).toHaveTextContent('Answered');
    expect(screen.getByRole('status')).toHaveTextContent(
      '1 of 2 answered; 1 failed. Last error: The assistant is busy, try again.',
    );
  });

  it('takes the user to sign-in when the session has expired', async () => {
    const assign = vi.spyOn(browser, 'assign').mockImplementation(() => {});
    mockFetch(routes({ 'POST /ask': () => problem(401, 'Unauthorized') }));
    render(<App />);
    await screen.findByRole('option', { name: 'acme' });

    await selectAndAsk(QUESTIONS[0].text);

    expect(await within(answers()).findByRole('alert')).toHaveTextContent(
      'Your session has expired. Taking you to sign-in…',
    );
    expect(assign).toHaveBeenCalledWith(expect.stringMatching(/^\/oauth2\/sign_in\?rd=/));
  });

  it('shows the signed-in user with a sign-out link', async () => {
    mockFetch(routes());
    render(<App />);

    const account = await screen.findByText('admin', { selector: '.user-menu__name' });
    await userEvent.setup().click(account);

    expect(screen.getByText('admin@helpdesk.local')).toBeVisible();
    expect(within(screen.getByRole('list', { name: 'Roles' })).getByText('Admin')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign out' })).toHaveAttribute('href', '/oauth2/sign_out');
  });

  it('lets only admins add catalog questions', async () => {
    mockFetch(
      routes({
        'GET /oauth2/userinfo': () => json({ user: 'u2', preferredUsername: 'acme-agent', groups: ['helpdesk-agent'] }),
      }),
    );
    render(<App />);

    expect(await screen.findByText('Only admins can add questions.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'New question' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add to list' })).toBeDisabled();
  });

  it('works without oauth2-proxy in front (dev server): no user menu, nothing restricted', async () => {
    mockFetch(routes({ 'GET /oauth2/userinfo': () => new Response('Not found', { status: 404 }) }));
    render(<App />);
    await screen.findByRole('option', { name: 'acme' });

    expect(screen.queryByRole('link', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'New question' })).toBeEnabled();
  });

  it('shows load errors for customers, questions and history', async () => {
    mockFetch(
      routes({
        'GET /customers': () => problem(503, 'Ticket server unavailable.'),
        'GET /questions': () => problem(503, 'Request log unavailable.'),
      }),
    );
    render(<App />);

    expect(await screen.findByText('Ticket server unavailable.')).toBeInTheDocument();
    expect(await screen.findByText('Request log unavailable.')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Customer' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled();
  });

  it('shows a history error for the selected customer', async () => {
    mockFetch(routes({ 'GET /requests': () => problem(503, 'History unavailable.') }));
    render(<App />);

    const history = screen.getByRole('table', { name: 'Asked questions' });
    expect(await within(history).findByText('History unavailable.')).toBeInTheDocument();
  });

  it('loads the history of the newly selected customer', async () => {
    const rows = (customer: string): LoggedRequest[] => [
      { requestId: customer, question: `Question for ${customer}`, status: 'SUCCESS', customer, createdAt: '' },
    ];
    mockFetch(routes({ 'GET /requests': (_, url) => json(rows(url.searchParams.get('customer') ?? '')) }));
    render(<App />);
    const history = screen.getByRole('table', { name: 'Asked questions' });
    expect(await within(history).findByText('Question for acme')).toBeInTheDocument();

    await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Customer' }), 'globex');

    expect(await within(history).findByText('Question for globex')).toBeInTheDocument();
    expect(within(history).queryByText('Question for acme')).not.toBeInTheDocument();
  });
});
