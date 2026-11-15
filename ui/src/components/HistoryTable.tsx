import type { LoggedRequest } from '../api';

const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
const timeFormat = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });

function formatDate(iso: string) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : `${dateFormat.format(d)}, ${timeFormat.format(d)}`;
}

type Props = {
  entries: LoggedRequest[];
  error: string | null;
};

export function HistoryTable({ entries, error }: Props) {
  return (
    <section className="history">
      <div className="history__heading">
        <h2>History</h2>
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
        {!error && entries.length === 0 && <div className="table__empty">No questions asked yet.</div>}
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

      <p className="hint">Most recent questions appear first.</p>
    </section>
  );
}
