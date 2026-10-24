import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmarEmail } from './ConfirmarEmail';
import { esquecerSessaoParaTestes } from './api';

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/problem+json' } });
const vazio = () => new Response(null, { status: 204 });

type Rota = (init: RequestInit) => Response;

/** fetch falso: cada "MÉTODO caminho" responde como a rota diz; guarda os corpos enviados. */
function api(rotas: Record<string, Rota>) {
  const chamadas: Array<{ chave: string; corpo: unknown }> = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url}`;
    chamadas.push({ chave, corpo: init.body ? JSON.parse(init.body as string) : null });
    if (chave === 'POST /api/auth/renovar') return json(200, { token: 't' });
    const rota = rotas[chave];
    return rota ? rota(init) : json(404, {});
  }));
  return chamadas;
}

describe('confirmar o e-mail (ADR 0038)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    esquecerSessaoParaTestes();
  });

  it('envia o código, confirma com 6 dígitos e avisa quem chamou', async () => {
    const chamadas = api({ 'POST /api/auth/email/codigo': vazio, 'POST /api/auth/email/confirmar': vazio });
    const aoConfirmar = vi.fn();
    render(<ConfirmarEmail email="ana@academia.com" aoConfirmar={aoConfirmar} />);

    expect(screen.queryByLabelText('Código')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    const campo = await screen.findByLabelText('Código');
    await userEvent.type(campo, '12a3456');
    expect(campo).toHaveValue('123456');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    await vi.waitFor(() => expect(aoConfirmar).toHaveBeenCalled());
    expect(chamadas.find((c) => c.chave === 'POST /api/auth/email/confirmar')?.corpo).toEqual({ codigo: '123456' });
  });

  it('código errado: mostra o erro e não entra', async () => {
    api({ 'POST /api/auth/email/codigo': vazio,
      'POST /api/auth/email/confirmar': () => json(400, { detail: 'Código inválido ou vencido. Confira o e-mail mais recente ou peça outro código.' }) });
    const aoConfirmar = vi.fn();
    render(<ConfirmarEmail email="ana@academia.com" aoConfirmar={aoConfirmar} />);

    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    await userEvent.type(await screen.findByLabelText('Código'), '000000');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(await screen.findByText(/Código inválido ou vencido/)).toBeInTheDocument();
    expect(aoConfirmar).not.toHaveBeenCalled();
  });

  it('confirmar só com 6 dígitos', async () => {
    api({ 'POST /api/auth/email/codigo': vazio });
    render(<ConfirmarEmail email="ana@academia.com" aoConfirmar={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    await userEvent.type(await screen.findByLabelText('Código'), '12345');

    expect(screen.getByRole('button', { name: 'Confirmar' })).toBeDisabled();
  });

  it('e-mail errado: corrige, e o código vai para o novo', async () => {
    const chamadas = api({ 'PUT /api/auth/email': vazio });
    render(<ConfirmarEmail email="ana@errado.com" aoConfirmar={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'O e-mail está errado?' }));
    await userEvent.type(screen.getByLabelText('E-mail certo'), 'Ana@Certo.com');
    await userEvent.click(screen.getByRole('button', { name: 'Corrigir e enviar código' }));

    expect(await screen.findByLabelText('Código')).toBeInTheDocument();
    expect(screen.getByText('ana@certo.com')).toBeInTheDocument();
    expect(chamadas.find((c) => c.chave === 'PUT /api/auth/email')?.corpo).toEqual({ email: 'Ana@Certo.com' });
  });

  it('pediu de novo cedo demais: mostra o aviso da API', async () => {
    api({ 'POST /api/auth/email/codigo': () => json(429, { detail: 'Muitos códigos pedidos. Espere um pouco e peça de novo.' }) });
    render(<ConfirmarEmail email="ana@academia.com" aoConfirmar={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));

    expect(await screen.findByText(/Muitos códigos pedidos/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Código')).not.toBeInTheDocument();
  });
});
