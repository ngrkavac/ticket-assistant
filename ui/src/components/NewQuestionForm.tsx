import { useState } from 'react';
import { addQuestion, type CatalogQuestion } from '../api';

type Props = {
  disabled: boolean;
  onAdded: (question: CatalogQuestion) => void;
};

export function NewQuestionForm({ disabled, onAdded }: Props) {
  const [text, setText] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    const value = text.trim();
    if (!value) {
      setError('Enter a question first.');
      return;
    }
    setSaving(true);
    try {
      onAdded(await addQuestion(value));
      setText('');
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      className="field"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <label className="section-title" htmlFor="new-question">
        New question
      </label>
      <div className="picker__controls">
        <input
          id="new-question"
          className="input"
          type="text"
          maxLength={1000}
          placeholder="e.g. Is the printer on the 2nd floor working?"
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={disabled || saving}
        />
        <button className="button button--secondary" type="submit" disabled={disabled || saving}>
          {saving ? 'Saving…' : 'Add to list'}
        </button>
      </div>
      <p className={`hint${error ? ' hint--error' : ''}`} role={error ? 'alert' : undefined}>
        {error ?? 'Saved for everyone and selected, ready to ask.'}
      </p>
    </form>
  );
}
