import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { UserMenu } from './UserMenu';

const USER = { name: 'acme-writer', email: 'acme-writer@helpdesk.local', roles: ['helpdesk-agent', 'tickets-write', 'offline'] };

function menu() {
  return screen.getByRole('group') as HTMLDetailsElement;
}

describe('UserMenu', () => {
  it('shows the user, the known roles and a sign-out link', async () => {
    render(<UserMenu user={USER} />);
    expect(menu().open).toBe(false);

    await userEvent.setup().click(screen.getByLabelText('Account: acme-writer'));

    expect(menu().open).toBe(true);
    expect(screen.getByText('acme-writer@helpdesk.local')).toBeInTheDocument();
    const roles = screen.getByRole('list', { name: 'Roles' });
    expect(roles).toHaveTextContent('Agent');
    expect(roles).toHaveTextContent('Can create tickets');
    expect(roles).not.toHaveTextContent('offline');
    expect(screen.getByRole('link', { name: 'Sign out' })).toHaveAttribute('href', '/oauth2/sign_out');
  });

  it('closes on Escape and on a click outside', async () => {
    const user = userEvent.setup();
    render(
      <>
        <UserMenu user={{ name: 'admin', email: null, roles: [] }} />
        <button type="button">Elsewhere</button>
      </>,
    );
    const summary = screen.getByLabelText('Account: admin');

    await user.click(summary);
    await user.keyboard('{Escape}');
    expect(menu().open).toBe(false);
    expect(summary).toHaveFocus();
    expect(screen.queryByRole('list', { name: 'Roles' })).not.toBeInTheDocument();

    await user.click(summary);
    await user.click(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(menu().open).toBe(false);
  });
});
