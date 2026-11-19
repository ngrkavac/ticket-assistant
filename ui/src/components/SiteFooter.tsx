import { Logo } from './Logo';

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer__inner">
        <div className="site-footer__brand">
          <Logo size={24} />
          <span className="site-footer__tagline">Helpdesk answers, resolved.</span>
        </div>
        <p className="site-footer__meta">
          Built with Spring AI, MCP and Ollama · © {new Date().getFullYear()} Resolva
        </p>
      </div>
    </footer>
  );
}
