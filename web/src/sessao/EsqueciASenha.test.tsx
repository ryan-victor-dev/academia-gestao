import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TelaDeLogin } from './TelaDeLogin';

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/problem+json' } });

/** fetch falso por "MÉTODO caminho"; guarda os corpos enviados. */
function api(rotas: Record<string, () => Response>) {
  const chamadas: Array<{ chave: string; corpo: unknown }> = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const chave = `${init.method ?? 'GET'} ${url}`;
    chamadas.push({ chave, corpo: init.body ? JSON.parse(init.body as string) : null });
    return rotas[chave]?.() ?? json(404, {});
  }));
  return chamadas;
}
const ligado = { 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: true }) };

describe('esqueci a senha (ADR 0038)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('desligado: o login não oferece', async () => {
    const chamadas = api({ 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: false }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await vi.waitFor(() => expect(chamadas.map((c) => c.chave)).toContain('GET /api/auth/opcoes'));
    expect(screen.queryByRole('button', { name: 'Esqueci a senha' })).not.toBeInTheDocument();
  });

  it('pede o código com o e-mail digitado, cria a senha nova e volta ao login', async () => {
    const chamadas = api({ ...ligado, 'POST /api/auth/esqueci-senha': () => new Response(null, { status: 202 }),
      'POST /api/auth/redefinir-senha': () => new Response(null, { status: 204 }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await userEvent.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));
    expect(screen.getByLabelText('E-mail')).toHaveValue('ana@academia.com');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));

    expect(await screen.findByRole('heading', { name: 'Criar uma senha nova' })).toBeInTheDocument();
    expect(screen.getByText(/estiver cadastrado e confirmado/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Código'), '12a3456');
    await userEvent.type(screen.getByLabelText(/Nova senha/), 'senha-nova-bem-forte');
    await userEvent.type(screen.getByLabelText('Repita a nova senha'), 'senha-nova-bem-forte');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(await screen.findByText('Senha nova criada. Entre com ela.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(chamadas.find((c) => c.chave === 'POST /api/auth/esqueci-senha')?.corpo).toEqual({ email: 'ana@academia.com' });
    expect(chamadas.find((c) => c.chave === 'POST /api/auth/redefinir-senha')?.corpo)
      .toEqual({ email: 'ana@academia.com', codigo: '123456', novaSenha: 'senha-nova-bem-forte' });
  });

  it('só salva com 6 dígitos, a senha com 12 caracteres e a confirmação igual', async () => {
    api({ ...ligado, 'POST /api/auth/esqueci-senha': () => new Response(null, { status: 202 }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    const salvar = await screen.findByRole('button', { name: 'Salvar nova senha' });

    await userEvent.type(screen.getByLabelText('Código'), '12345');
    await userEvent.type(screen.getByLabelText(/Nova senha/), 'curta');
    expect(screen.getByText(/pelo menos 12 caracteres/)).toBeInTheDocument();
    expect(salvar).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Código'), '6');
    await userEvent.type(screen.getByLabelText(/Nova senha/), '-agora-longa');
    await userEvent.type(screen.getByLabelText('Repita a nova senha'), 'diferente-da-outra');
    expect(screen.getByText(/não é igual/)).toBeInTheDocument();
    expect(salvar).toBeDisabled();
  });

  it('com tudo certo, só falta o sexto dígito do código', async () => {
    api({ ...ligado, 'POST /api/auth/esqueci-senha': () => new Response(null, { status: 202 }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    await userEvent.type(await screen.findByLabelText(/Nova senha/), 'senha-nova-bem-forte');
    await userEvent.type(screen.getByLabelText('Repita a nova senha'), 'senha-nova-bem-forte');

    await userEvent.type(screen.getByLabelText('Código'), '12345');
    expect(screen.getByRole('button', { name: 'Salvar nova senha' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Código'), '6');
    expect(screen.getByRole('button', { name: 'Salvar nova senha' })).toBeEnabled();
  });

  it('as opções falharam (servidor com erro): o login não oferece', async () => {
    const chamadas = api({ 'GET /api/auth/opcoes': () => json(500, {}) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await vi.waitFor(() => expect(chamadas.map((c) => c.chave)).toContain('GET /api/auth/opcoes'));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByRole('button', { name: 'Esqueci a senha' })).not.toBeInTheDocument();
  });

  it('código errado: mostra o erro da API e fica', async () => {
    api({ ...ligado, 'POST /api/auth/esqueci-senha': () => new Response(null, { status: 202 }),
      'POST /api/auth/redefinir-senha': () => json(400, { detail: 'Código inválido ou vencido. Confira o e-mail mais recente ou peça outro código.' }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    await userEvent.type(await screen.findByLabelText('Código'), '000000');
    await userEvent.type(screen.getByLabelText(/Nova senha/), 'senha-nova-bem-forte');
    await userEvent.type(screen.getByLabelText('Repita a nova senha'), 'senha-nova-bem-forte');

    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(await screen.findByText(/Código inválido ou vencido/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Criar uma senha nova' })).toBeInTheDocument();
  });

  it('voltar leva ao login sem o aviso de senha criada', async () => {
    api(ligado);
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));

    await userEvent.click(screen.getByRole('button', { name: 'Voltar para o login' }));

    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.queryByText(/Senha nova criada/)).not.toBeInTheDocument();
  });
});
