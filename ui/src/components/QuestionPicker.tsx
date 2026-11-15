import { useState } from 'react';
import { QUESTIONS } from '../questions';

type Props = {
  busy: boolean;
  message: { text: string; error?: boolean } | null;
  onAsk: (question: string) => void;
};

export function QuestionPicker({ busy, message, onAsk }: Props) {
  const [question, setQuestion] = useState<string>(QUESTIONS[0]);

  return (
    <form
      className="picker"
      onSubmit={(e) => {
        e.preventDefault();
        onAsk(question);
      }}
    >
      <label className="picker__label" htmlFor="question">
        Predefined question
      </label>
      <div className="picker__controls">
        <div className="select">
          <select id="question" value={question} onChange={(e) => setQuestion(e.target.value)} disabled={busy}>
            {QUESTIONS.map((q) => (
              <option key={q} value={q}>
                {q}
              </option>
            ))}
          </select>
          <span className="select__chevron" aria-hidden="true">
            ⌄
          </span>
        </div>
        <button className="button" type="submit" disabled={busy}>
          {busy ? 'Asking…' : 'Ask'}
        </button>
      </div>
      <p className={`hint${message?.error ? ' hint--error' : ''}`} role="status">
        {message?.text ?? `${QUESTIONS.length} questions available. Each one is sent to the assistant and stored in the request log.`}
      </p>
    </form>
  );
}
