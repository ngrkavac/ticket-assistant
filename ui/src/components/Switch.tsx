type Props = {
  id: string;
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
};

export function Switch({ id, label, checked, onChange }: Props) {
  return (
    <label className="switch" htmlFor={id}>
      <input
        id={id}
        className="switch__input"
        type="checkbox"
        role="switch"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="switch__track" aria-hidden="true" />
      <span className="switch__label">{label}</span>
    </label>
  );
}
