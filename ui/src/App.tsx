import { useCallback, useEffect, useState } from 'react';
import { ask, listRequests, type LoggedRequest } from './api';
import { HistoryTable } from './components/HistoryTable';
import { QuestionPicker } from './components/QuestionPicker';

// The assistant stores each request in the background, so give it a moment before re-reading.
const LOG_WRITE_DELAY_MS = 500;

export default function App() {
  const [entries, setEntries] = useState<LoggedRequest[]>([]);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error?: boolean } | null>(null);

  const refresh = useCallback(async () => {
    try {
      setEntries(await listRequests());
      setHistoryError(null);
    } catch (e) {
      setHistoryError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function handleAsk(question: string) {
    setBusy(true);
    setMessage({ text: 'Waiting for the assistant… this can take a while with a local model.' });
    try {
      await ask(question);
      setMessage({ text: 'Answered and added to the history.' });
    } catch (e) {
      setMessage({ text: (e as Error).message, error: true });
    } finally {
      setBusy(false);
      setTimeout(() => void refresh(), LOG_WRITE_DELAY_MS);
    }
  }

  return (
    <main className="page">
      <header className="page__heading">
        <h1>Question history</h1>
        <p>Choose a predefined question and ask the helpdesk assistant.</p>
      </header>
      <QuestionPicker busy={busy} message={message} onAsk={handleAsk} />
      <HistoryTable entries={entries} error={historyError} />
    </main>
  );
}
