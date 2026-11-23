import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AnswerList, type AnswerResult } from './AnswerList';

function items() {
  return within(screen.getByRole('list')).getAllByRole('listitem');
}

describe('AnswerList', () => {
  it('shows a hint and no customer before anything was asked', () => {
    render(<AnswerList customer={null} results={[]} />);

    expect(screen.getByRole('heading', { name: 'Answers' })).toBeInTheDocument();
    expect(screen.getByText('0 questions')).toBeInTheDocument();
    expect(screen.getByText('Answers to the questions you ask appear here.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows one entry per question with its state, answer or error', () => {
    const results: AnswerResult[] = [
      { key: '1', question: 'Open tickets?', state: 'answered', answer: 'Two tickets are open.' },
      { key: '2', question: 'Any outages?', state: 'failed', error: 'The assistant is busy.' },
      { key: '3', question: 'Who is the contact?', state: 'asking' },
      { key: '4', question: 'Next renewal?', state: 'pending' },
    ];
    render(<AnswerList customer="acme" results={results} />);

    expect(screen.getByRole('heading', { name: 'Answers for acme' })).toBeInTheDocument();
    expect(screen.getByText('4 questions')).toBeInTheDocument();
    const [answered, failed, asking, pending] = items();

    expect(within(answered).getByText('Open tickets?')).toBeInTheDocument();
    expect(within(answered).getByText('Answered')).toBeInTheDocument();
    expect(within(answered).getByText('Two tickets are open.')).toBeInTheDocument();
    expect(within(answered).queryByRole('alert')).not.toBeInTheDocument();

    expect(within(failed).getByText('Failed')).toBeInTheDocument();
    expect(within(failed).getByRole('alert')).toHaveTextContent('The assistant is busy.');

    expect(within(asking).getByText('Asking…')).toBeInTheDocument();
    expect(asking).toHaveAttribute('aria-busy', 'true');

    expect(within(pending).getByText('Waiting')).toBeInTheDocument();
    expect(pending).toHaveAttribute('aria-busy', 'false');
  });

  it('keeps line breaks in the answer text', () => {
    const answer = 'Line one\nLine two';
    render(<AnswerList customer="acme" results={[{ key: '1', question: 'Q', state: 'answered', answer }]} />);

    expect(screen.getByText(/Line one/).textContent).toBe(answer);
    expect(screen.getByText('1 question')).toBeInTheDocument();
  });
});
