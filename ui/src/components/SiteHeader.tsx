import type { User } from '../api';
import { Logo } from './Logo';
import { ThemeToggle } from './ThemeToggle';
import { UserMenu } from './UserMenu';

type Props = { user?: User | null };

export function SiteHeader({ user = null }: Props) {
  return (
    <header className="site-header">
      <div className="site-header__inner">
        <a className="site-header__brand" href="#top" aria-label="Resolva home">
          <Logo />
        </a>
        <nav className="site-header__nav" aria-label="Main">
          <a href="#features">How it works</a>
          <a href="#workspace">Workspace</a>
        </nav>
        <ThemeToggle />
        {user && <UserMenu user={user} />}
      </div>
    </header>
  );
}
