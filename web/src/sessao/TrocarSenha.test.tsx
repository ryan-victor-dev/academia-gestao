import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TrocarSenha } from './TrocarSenha';

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } });

async function preencher(atual: string, nova: string, confirmacao = nova) {
  if (atual) await userEvent.type(screen.getByLabelText('Senha atual'), atual);
  if (nova) await userEvent.type(screen.getByLabelText(/Nova senha/), nova);
  if (confirmacao) await userEvent.type(screen.getByLabelText('Repita a nova senha'), confirmacao);
}

describe('trocar senha', () => {
  it('troca e avisa quem abriu', async () => {
    const fetch = vi.fn(async (url: string, _init?: RequestInit) =>
      url === '/api/auth/renovar' ? json(200, { token: 't' }) : json(200, { token: 'novo' }));
    vi.stubGlobal('fetch', fetch);
    const aoTrocar = vi.fn();
    render(<TrocarSenha obrigatoria aoTrocar={aoTrocar} />);

    await preencher('temporaria-123', 'minha-senha-nova-1');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(aoTrocar).toHaveBeenCalledOnce();
    const troca = fetch.mock.calls.find((c) => c[0] === '/api/auth/trocar-senha')!;
    expect(JSON.parse(troca[1]!.body as string)).toEqual({ senhaAtual: 'temporaria-123', novaSenha: 'minha-senha-nova-1' });
  });

  it('senha curta: avisa antes de enviar', async () => {
    render(<TrocarSenha obrigatoria aoTrocar={vi.fn()} />);

    await preencher('temporaria-123', 'curta');

    expect(screen.getByRole('alert')).toHaveTextContent('pelo menos 12 caracteres');
    expect(screen.getByRole('button', { name: 'Salvar nova senha' })).toBeDisabled();
  });

  it('confirmação diferente: avisa e não envia', async () => {
    render(<TrocarSenha obrigatoria aoTrocar={vi.fn()} />);

    await preencher('temporaria-123', 'minha-senha-nova-1', 'minha-senha-nova-2');

    expect(screen.getByRole('alert')).toHaveTextContent('A confirmação não é igual');
    expect(screen.getByRole('button', { name: 'Salvar nova senha' })).toBeDisabled();
  });

  it('nova igual à atual: avisa e não envia', async () => {
    render(<TrocarSenha obrigatoria aoTrocar={vi.fn()} />);

    await preencher('mesma-senha-1234', 'mesma-senha-1234');

    expect(screen.getByRole('alert')).toHaveTextContent('diferente da atual');
    expect(screen.getByRole('button', { name: 'Salvar nova senha' })).toBeDisabled();
  });

  it('senha atual errada: mostra a explicação da API', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) =>
      url === '/api/auth/renovar' ? json(200, { token: 't' }) : json(400, { detail: 'senha atual incorreta' })));
    const aoTrocar = vi.fn();
    render(<TrocarSenha obrigatoria aoTrocar={aoTrocar} />);

    await preencher('errada-123456', 'minha-senha-nova-1');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Senha atual incorreta.');
    expect(aoTrocar).not.toHaveBeenCalled();
  });

  it('troca voluntária: confirma na tela', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { token: 'novo' })));
    render(<TrocarSenha obrigatoria={false} aoTrocar={vi.fn()} />);

    await preencher('senha-antiga-123', 'minha-senha-nova-1');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Senha trocada');
  });
});
