import { useId } from 'react';

type Props = {
  size?: number;
  withWordmark?: boolean;
};

/** Resolva mark: a speech bubble with a check mark, "a question, resolved". Same artwork as public/favicon.svg. */
export function Logo({ size = 32, withWordmark = true }: Props) {
  // Unique per instance, so the header and footer logos don't share a gradient id.
  const gradientId = useId();

  return (
    <span className="logo">
      <svg className="logo__mark" width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#6366f1" />
            <stop offset="1" stopColor="#4338ca" />
          </linearGradient>
        </defs>
        <path
          d="M8 3h16a6 6 0 0 1 6 6v9a6 6 0 0 1-6 6h-9l-6 5.5V24H8a6 6 0 0 1-6-6V9a6 6 0 0 1 6-6z"
          fill={`url(#${gradientId})`}
        />
        <path
          d="M10.5 13.5l3.5 3.5 7-7.5"
          fill="none"
          stroke="#fff"
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      {withWordmark && <span className="logo__wordmark">Resolva</span>}
    </span>
  );
}
