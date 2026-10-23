import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } });
const ana = { id: '1', nome: 'Ana', email: 'ana@academia.com', perfil: 'RECEPCAO', ativo: true,
  precisaTrocarSenha: false, somenteLeitura: false };

describe('App', () => {
  it('com a sessão do cookie, abre já dentro, sem pedir a senha', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      url === '/api/auth/renovar' ? json(200, { token: 't' }) : json(200, ana)));

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument();
    expect(screen.getByText('Recepção')).toBeInTheDocument();
  });

  it('sem sessão, mostra o login', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, {})));

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
  });

  it('sair volta ao login e encerra a sessão no servidor', async () => {
    const fetch = vi.fn(async (url: string) =>
      url === '/api/auth/renovar' ? json(200, { token: 't' })
        : url === '/api/auth/sair' ? new Response(null, { status: 204 }) : json(200, ana));
    vi.stubGlobal('fetch', fetch);
    render(<App />);

    await userEvent.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(fetch.mock.calls.map((c) => c[0])).toContain('/api/auth/sair');
  });
});
