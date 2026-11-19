type Props = {
  customers: string[];
  value: string | null;
  error: string | null;
  disabled: boolean;
  onChange: (customer: string) => void;
};

export function CustomerPicker({ customers, value, error, disabled, onChange }: Props) {
  return (
    <div className="field">
      <label className="field__label" htmlFor="customer">
        Customer
      </label>
      <div className="select">
        <select
          id="customer"
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled || customers.length === 0}
        >
          {customers.length === 0 && <option value="">{error ? 'Unavailable' : 'Loading…'}</option>}
          {customers.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        <span className="select__chevron" aria-hidden="true">
          ⌄
        </span>
      </div>
      {error && (
        <p className="hint hint--error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
