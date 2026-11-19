import type { LoggedRequest } from '../api';

const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
const timeFormat = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });

function formatDate(iso: string) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : `${dateFormat.format(d)}, ${timeFormat.format(d)}`;
}

type Props = {
  customer: string | null;
  limit: number;
  entries: LoggedRequest[];
  error: string | null;
};

export function HistoryTable({ customer, limit, entries, error }: Props) {
  return (
    <section className="history">
      <div className="history__heading">
        <h2 className="section-title">History{customer ? ` for ${customer}` : ''}</h2>
        <span className="badge">
          {entries.length} {entries.length === 1 ? 'entry' : 'entries'}
        </span>
      </div>

      <div className="table" role="table" aria-label="Asked questions">
        <div className="table__row table__row--head" role="row">
          <span role="columnheader">#</span>
          <span role="columnheader">Question</span>
          <span role="columnheader">Date asked</span>
        </div>
        {error && <div className="table__empty table__empty--error">{error}</div>}
        {!error && entries.length === 0 && (
          <div className="table__empty">
            {customer ? `No questions asked for ${customer} yet.` : 'Choose a customer to see its history.'}
          </div>
        )}
        {entries.map((entry, i) => (
          <div className="table__row" role="row" key={entry.requestId}>
            <span className="table__index" role="cell">
              {String(entries.length - i).padStart(2, '0')}
            </span>
            <span className="table__question" role="cell" title={entry.question}>
              {entry.question}
              {entry.status === 'ERROR' && <span className="tag-error">failed</span>}
            </span>
            <span className="table__date" role="cell">
              {formatDate(entry.createdAt)}
            </span>
          </div>
        ))}
      </div>

      <p className="hint">The last {limit} questions for the selected customer, most recent first.</p>
    </section>
  );
}
