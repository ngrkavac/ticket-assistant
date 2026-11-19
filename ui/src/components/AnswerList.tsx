export type AnswerResult = {
  key: string;
  question: string;
  state: 'pending' | 'asking' | 'answered' | 'failed';
  answer?: string;
  error?: string;
};

type Props = {
  customer: string | null;
  results: AnswerResult[];
};

const STATE_LABEL: Record<AnswerResult['state'], string> = {
  pending: 'Waiting',
  asking: 'Asking…',
  answered: 'Answered',
  failed: 'Failed',
};

export function AnswerList({ customer, results }: Props) {
  return (
    <section className="answers" aria-labelledby="answers-title">
      <div className="history__heading">
        <h2 id="answers-title" className="section-title">
          Answers{customer ? ` for ${customer}` : ''}
        </h2>
        <span className="badge">
          {results.length} {results.length === 1 ? 'question' : 'questions'}
        </span>
      </div>
      {results.length === 0 ? (
        <p className="hint">Answers to the questions you ask appear here.</p>
      ) : (
        <ol className="answers__list" aria-live="polite">
          {results.map((r) => (
            <li className={`answers__item answers__item--${r.state}`} key={r.key} aria-busy={r.state === 'asking'}>
              <div className="answers__head">
                <span className="answers__question">{r.question}</span>
                <span className={`answers__state answers__state--${r.state}`}>{STATE_LABEL[r.state]}</span>
              </div>
              {r.state === 'answered' && <p className="answers__text">{r.answer}</p>}
              {r.state === 'failed' && (
                <p className="answers__text answers__text--error" role="alert">
                  {r.error}
                </p>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
