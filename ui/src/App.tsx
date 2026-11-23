import { useCallback, useEffect, useRef, useState } from 'react';
import { ask, listCustomers, listQuestions, listRequests, type CatalogQuestion, type LoggedRequest } from './api';
import { AnswerList, type AnswerResult } from './components/AnswerList';
import { CustomerPicker } from './components/CustomerPicker';
import { Hero } from './components/Hero';
import { HistoryTable } from './components/HistoryTable';
import { NewQuestionForm } from './components/NewQuestionForm';
import { QuestionPicker } from './components/QuestionPicker';
import { SiteFooter } from './components/SiteFooter';
import { SiteHeader } from './components/SiteHeader';
import { Switch } from './components/Switch';

// The assistant stores each request in the background, so give it a moment before re-reading.
const LOG_WRITE_DELAY_MS = 500;
const HISTORY_SIZE = 5;

type Message = { text: string; error?: boolean };
type History = { customer: string; rows: LoggedRequest[]; error: string | null };

export default function App() {
  const [customers, setCustomers] = useState<string[]>([]);
  const [customer, setCustomer] = useState<string | null>(null);
  const [customerError, setCustomerError] = useState<string | null>(null);
  const [questions, setQuestions] = useState<CatalogQuestion[]>([]);
  const [questionsError, setQuestionsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  // Tagged with the customer it belongs to, so switching customers hides it without an extra reset.
  const [history, setHistory] = useState<History | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [showHistory, setShowHistory] = useState(true);
  // Answers to the most recent batch, for the customer it was asked about.
  const [results, setResults] = useState<AnswerResult[]>([]);
  const [resultsCustomer, setResultsCustomer] = useState<string | null>(null);
  // Ignore history responses for a customer that is no longer selected.
  const customerRef = useRef<string | null>(null);
  useEffect(() => {
    customerRef.current = customer;
  }, [customer]);

  const refresh = useCallback((forCustomer: string) => {
    listRequests(forCustomer, HISTORY_SIZE).then(
      (rows) => {
        if (customerRef.current === forCustomer) {
          setHistory({ customer: forCustomer, rows, error: null });
        }
      },
      (e) => {
        if (customerRef.current === forCustomer) {
          const error = (e as Error).message;
          // Keep the rows already shown for this customer next to the error.
          setHistory((prev) => ({ customer: forCustomer, rows: prev?.customer === forCustomer ? prev.rows : [], error }));
        }
      },
    );
  }, []);
  const current = history?.customer === customer ? history : null;

  useEffect(() => {
    listCustomers()
      .then((names) => {
        setCustomers(names);
        setCustomer((current) => current ?? names[0] ?? null);
      })
      .catch((e) => setCustomerError((e as Error).message));
    listQuestions()
      .then(setQuestions)
      .catch((e) => setQuestionsError((e as Error).message));
  }, []);

  useEffect(() => {
    if (customer) {
      refresh(customer);
    }
  }, [customer, refresh]);

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function handleAdded(question: CatalogQuestion) {
    setQuestions((prev) => (prev.some((q) => q.id === question.id) ? prev : [...prev, question]));
    setSelected((prev) => new Set(prev).add(question.id));
  }

  // Questions are asked one after another: a local model is slow and the server caps concurrency.
  async function handleAsk() {
    if (!customer) {
      return;
    }
    const batch = questions.filter((q) => selected.has(q.id));
    const batchId = Date.now();
    const update = (i: number, patch: Partial<AnswerResult>) =>
      setResults((prev) => prev.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    setResults(batch.map((q, i) => ({ key: `${batchId}-${i}`, question: q.text, state: 'pending' })));
    setResultsCustomer(customer);
    setBusy(true);
    let failed = 0;
    let lastError = '';
    for (const [i, q] of batch.entries()) {
      setMessage({ text: `Asking ${i + 1} of ${batch.length} for ${customer}… this can take a while with a local model.` });
      update(i, { state: 'asking' });
      try {
        const { answer } = await ask(q.text, customer);
        update(i, { state: 'answered', answer });
      } catch (e) {
        failed++;
        lastError = (e as Error).message;
        update(i, { state: 'failed', error: lastError });
      }
      setTimeout(() => refresh(customer), LOG_WRITE_DELAY_MS);
    }
    const done = batch.length - failed;
    setMessage(
      failed === 0
        ? { text: `${done} ${done === 1 ? 'question' : 'questions'} answered and added to the history.` }
        : { text: `${done} of ${batch.length} answered; ${failed} failed. Last error: ${lastError}`, error: true },
    );
    setSelected(new Set());
    setBusy(false);
  }

  return (
    <div className="site" id="top">
      <SiteHeader />
      <main className="page">
        <Hero />
        <section id="workspace" className="workspace-section" aria-labelledby="workspace-title">
          <header className="page__heading">
            <h2 id="workspace-title">Workspace</h2>
            <p>Choose a customer, pick one or more questions and ask the helpdesk assistant.</p>
          </header>
          <div className="workspace">
            <section className="workspace__column" aria-label="Ask questions">
              <CustomerPicker
                customers={customers}
                value={customer}
                error={customerError}
                disabled={busy}
                onChange={setCustomer}
              />
              <QuestionPicker
                questions={questions}
                error={questionsError}
                selected={selected}
                busy={busy}
                canAsk={customer !== null}
                message={message}
                onToggle={toggle}
                onAsk={handleAsk}
              />
              <AnswerList customer={resultsCustomer} results={results} />
            </section>
            <div className="workspace__divider" aria-hidden="true" />
            <section className="workspace__column" aria-label="New question and history">
              <NewQuestionForm disabled={busy} onAdded={handleAdded} />
              <Switch id="show-history" label="Show history" checked={showHistory} onChange={setShowHistory} />
              {showHistory && (
                <HistoryTable
                  customer={customer}
                  limit={HISTORY_SIZE}
                  entries={current?.rows ?? []}
                  error={current?.error ?? null}
                />
              )}
            </section>
          </div>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
