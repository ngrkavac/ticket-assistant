import type { ReactNode } from 'react';

type Feature = { title: string; text: string; icon: ReactNode };

const iconProps = {
  width: 20,
  height: 20,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
};

const FEATURES: Feature[] = [
  {
    title: 'Knowledge base answers',
    text: "Step-by-step fixes drawn from your team's own runbooks and guides.",
    icon: (
      <svg {...iconProps}>
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5v14z" />
        <path d="M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5" />
      </svg>
    ),
  },
  {
    title: 'Live ticket context',
    text: 'Checks open tickets and their status for the selected customer before it answers.',
    icon: (
      <svg {...iconProps}>
        <path d="M3 8a2 2 0 0 0 0 4v4a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4a2 2 0 0 0 0-4V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2z" />
        <path d="M14 4v14" strokeDasharray="2 2" />
      </svg>
    ),
  },
  {
    title: 'Private by design',
    text: 'Runs on a local model, so customer data never goes to a third-party AI service.',
    icon: (
      <svg {...iconProps}>
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
        <path d="M9 12l2 2 4-4" />
      </svg>
    ),
  },
];

export function Hero() {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="hero__copy">
        <span className="eyebrow">AI helpdesk assistant for support teams</span>
        <h1 id="hero-title" className="hero__title">
          Resolve customer IT questions <span className="hero__accent">in seconds.</span>
        </h1>
        <p className="hero__mission">
          Resolva gives support agents one place to answer customer questions. It combines your team's knowledge
          base with live ticket data, and runs on a private local model, so customer data never leaves your network.
        </p>
        <div className="hero__actions">
          <a className="button button--lg" href="#workspace">
            Start asking
          </a>
          <a className="button button--secondary button--lg" href="#features">
            How it works
          </a>
        </div>
      </div>

      <ul id="features" className="features" aria-label="How it works">
        {FEATURES.map((f) => (
          <li className="feature" key={f.title}>
            <span className="feature__icon">{f.icon}</span>
            <h2 className="feature__title">{f.title}</h2>
            <p className="feature__text">{f.text}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}
