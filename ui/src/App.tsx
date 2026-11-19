import { useCallback, useEffect, useRef, useState } from 'react';
import { ask, listCustomers, listQuestions, listRequests, type CatalogQuestion, type LoggedRequest } from './api';
import { CustomerPicker } from './components/CustomerPicker';
import { HistoryTable } from './components/HistoryTable';
import { NewQuestionForm } from './components/NewQuestionForm';
import { QuestionPicker } from './components/QuestionPicker';
import { Switch } from './components/Switch';

// The assistant stores each request in the background, so give it a moment before re-reading.
const LOG_WRITE_DELAY_MS = 500;
const HISTORY_SIZE = 5;

type Message = { text: string; error?: boolean };

export default function App() {
  const [customers, setCustomers] = useState<string[]>([]);
  const [customer, setCustomer] = useState<string | null>(null);
  const [customerError, setCustomerError] = useState<string | null>(null);
  const [questions, setQuestions] = useState<CatalogQuestion[]>([]);
  const [questionsError, setQuestionsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [entries, setEntries] = useState<LoggedRequest[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [showHistory, setShowHistory] = useState(true);
  // Ignore history responses for a customer that is no longer selected.
  const customerRef = useRef<string | null>(null);
  customerRef.current = customer;

  const refresh = useCallback(async (forCustomer: string) => {
    try {
      const rows = await listRequests(forCustomer, HISTORY_SIZE);
      if (customerRef.current === forCustomer) {
        setEntries(rows);
        setHistoryError(null);
      }
    } catch (e) {
      if (customerRef.current === forCustomer) {
        setHistoryError((e as Error).message);
      }
    }
  }, []);

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
    setEntries([]);
    if (customer) {
      void refresh(customer);
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
    setBusy(true);
    let failed = 0;
    let lastError = '';
    for (const [i, q] of batch.entries()) {
      setMessage({ text: `Asking ${i + 1} of ${batch.length} for ${customer}… this can take a while with a local model.` });
      try {
        await ask(q.text, customer);
      } catch (e) {
        failed++;
        lastError = (e as Error).message;
      }
      setTimeout(() => void refresh(customer), LOG_WRITE_DELAY_MS);
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
    <main className="page">
      <header className="page__heading">
        <h1>Question history</h1>
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
        </section>
        <div className="workspace__divider" aria-hidden="true" />
        <section className="workspace__column" aria-label="New question and history">
          <NewQuestionForm disabled={busy} onAdded={handleAdded} />
          <Switch id="show-history" label="Show history" checked={showHistory} onChange={setShowHistory} />
          {showHistory && (
            <HistoryTable customer={customer} limit={HISTORY_SIZE} entries={entries} error={historyError} />
          )}
        </section>
      </div>
    </main>
  );
}
