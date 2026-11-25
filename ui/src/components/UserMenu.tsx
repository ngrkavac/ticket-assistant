import { useEffect, useRef } from 'react';
import { SIGN_OUT_PATH, type User } from '../api';

const ROLE_LABELS: Record<string, string> = {
  'helpdesk-agent': 'Agent',
  'helpdesk-admin': 'Admin',
  'tickets-write': 'Can create tickets',
};

type Props = { user: User };

/** Signed-in user with a sign-out link; a disclosure, so it works with keyboard and screen readers. */
export function UserMenu({ user }: Props) {
  const ref = useRef<HTMLDetailsElement>(null);

  // Close the menu on a click outside it or on Escape.
  useEffect(() => {
    const close = (e: Event) => {
      const menu = ref.current;
      if (!menu?.open) {
        return;
      }
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !menu.contains(e.target as Node)) {
        menu.open = false;
        if (e instanceof KeyboardEvent) {
          menu.querySelector('summary')?.focus();
        }
      }
    };
    document.addEventListener('click', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('click', close);
      document.removeEventListener('keydown', close);
    };
  }, []);

  const roles = user.roles.map((role) => ROLE_LABELS[role]).filter(Boolean);

  return (
    <details className="user-menu" ref={ref}>
      <summary className="user-menu__button" aria-label={`Account: ${user.name}`}>
        <span className="user-menu__avatar" aria-hidden="true">
          {user.name.charAt(0).toUpperCase()}
        </span>
        <span className="user-menu__name">{user.name}</span>
      </summary>
      <div className="user-menu__panel">
        <p className="user-menu__who">
          <span className="user-menu__label">Signed in as</span>
          <strong>{user.name}</strong>
          {user.email && <span className="user-menu__email">{user.email}</span>}
        </p>
        {roles.length > 0 && (
          <ul className="user-menu__roles" aria-label="Roles">
            {roles.map((role) => (
              <li key={role}>{role}</li>
            ))}
          </ul>
        )}
        <a className="button button--secondary user-menu__sign-out" href={SIGN_OUT_PATH}>
          Sign out
        </a>
      </div>
    </details>
  );
}
