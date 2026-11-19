import type { CatalogQuestion } from '../api';

type Props = {
  questions: CatalogQuestion[];
  error: string | null;
  selected: Set<number>;
  busy: boolean;
  canAsk: boolean;
  message: { text: string; error?: boolean } | null;
  onToggle: (id: number) => void;
  onAsk: () => void;
};

export function QuestionPicker({ questions, error, selected, busy, canAsk, message, onToggle, onAsk }: Props) {
  const count = selected.size;

  return (
    <form
      className="picker"
      onSubmit={(e) => {
        e.preventDefault();
        onAsk();
      }}
    >
      <fieldset className="checklist" disabled={busy}>
        <legend className="section-title">Questions</legend>
        {error && <p className="hint hint--error">{error}</p>}
        {!error && questions.length === 0 && <p className="hint">Loading questions…</p>}
        {questions.length > 0 && (
          <div className="checklist__box">
            {questions.map((q) => (
              <label className="checklist__item" key={q.id}>
                <input type="checkbox" checked={selected.has(q.id)} onChange={() => onToggle(q.id)} />
                <span>{q.text}</span>
              </label>
            ))}
          </div>
        )}
      </fieldset>
      <div className="picker__controls">
        <button className="button" type="submit" disabled={busy || count === 0 || !canAsk}>
          {busy ? 'Asking…' : count > 1 ? `Add ${count}` : 'Add'}
        </button>
      </div>
      <p className={`hint${message?.error ? ' hint--error' : ''}`} role="status">
        {message?.text ??
          `${count} of ${questions.length} selected. Each selected question is sent to the assistant for the chosen customer and stored in the request log.`}
      </p>
    </form>
  );
}
