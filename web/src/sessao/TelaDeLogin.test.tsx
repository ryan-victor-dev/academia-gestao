import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TelaDeLogin } from './TelaDeLogin';

const resposta = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } });

async function preencher() {
  await userEvent.type(screen.getByLabelText('E-mail'), ' ana@academia.com ');
  await userEvent.type(screen.getByLabelText('Senha'), 'senha-certa');
  await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
}

describe('tela de login', () => {
  it('entra com o e-mail sem os espaços das pontas e avisa quem a abriu', async () => {
    const fetch = vi.fn(async (_url: string, _init: RequestInit) => resposta(200, { token: 't', expiraEm: '', precisaTrocarSenha: false }));
    vi.stubGlobal('fetch', fetch);
    const aoEntrar = vi.fn();
    render(<TelaDeLogin aoEntrar={aoEntrar} />);

    await preencher();

    expect(aoEntrar).toHaveBeenCalledOnce();
    expect(JSON.parse(fetch.mock.calls[0][1].body as string)).toEqual({ email: 'ana@academia.com', senha: 'senha-certa' });
  });

  it('senha errada: mostra o erro e não entra', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => resposta(401, { detail: 'E-mail ou senha inválidos.' })));
    const aoEntrar = vi.fn();
    render(<TelaDeLogin aoEntrar={aoEntrar} />);

    await preencher();

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha inválidos.');
    expect(aoEntrar).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
  });

  it('muitas tentativas: mostra a explicação da API', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => resposta(429, { detail: 'Muitas tentativas. Tente em 15 minutos.' })));
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await preencher();

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente em 15 minutos.');
  });

  it('e-mail malformado (400): aponta os campos', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => resposta(400, { detail: 'Dados inválidos', erros: { email: 'inválido' } })));
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await preencher();

    expect(await screen.findByRole('alert')).toHaveTextContent('Confira o e-mail e a senha.');
  });

  it('não envia com campo vazio', () => {
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();
  });
});
