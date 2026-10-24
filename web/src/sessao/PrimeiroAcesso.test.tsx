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
const ligado = { 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: true, primeiroAcesso: true }) };
const pedido = { 'POST /api/auth/primeiro-acesso': () => new Response(null, { status: 202 }) };

async function abrirEPedir(cpf = '529.982.247-25', email = 'ana@academia.com') {
  await userEvent.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));
  await userEvent.type(screen.getByLabelText('CPF'), cpf);
  await userEvent.type(screen.getByLabelText('E-mail'), email);
  await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
}

async function preencherSenha(codigo: string, senha = 'senha-do-aluno-123', confirmacao = senha) {
  await userEvent.type(await screen.findByLabelText('Código'), codigo);
  await userEvent.type(screen.getByLabelText(/^Senha/), senha);
  if (confirmacao) await userEvent.type(screen.getByLabelText('Repita a senha'), confirmacao);
}

describe('primeiro acesso do aluno (ADR 0039)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('desligado: o login não oferece', async () => {
    const chamadas = api({ 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: false, primeiroAcesso: false }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await vi.waitFor(() => expect(chamadas.map((c) => c.chave)).toContain('GET /api/auth/opcoes'));
    expect(screen.queryByRole('button', { name: 'Primeiro acesso (aluno)' })).not.toBeInTheDocument();
  });

  it('servidor antigo, sem a opção: o login não oferece', async () => {
    const chamadas = api({ 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: true }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await screen.findByRole('button', { name: 'Esqueci a senha' });
    expect(chamadas.map((c) => c.chave)).toContain('GET /api/auth/opcoes');
    expect(screen.queryByRole('button', { name: 'Primeiro acesso (aluno)' })).not.toBeInTheDocument();
  });

  it('pede o código com o CPF só com os números, cria a senha e volta ao login com o aviso', async () => {
    const chamadas = api({ ...ligado, ...pedido,
      'POST /api/auth/primeiro-acesso/senha': () => new Response(null, { status: 204 }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await abrirEPedir();

    expect(await screen.findByRole('heading', { name: 'Criar a sua senha' })).toBeInTheDocument();
    expect(screen.getByText(/aluno ativo ainda sem login/)).toBeInTheDocument();
    await preencherSenha('12a3456');
    await userEvent.click(screen.getByRole('button', { name: 'Criar o acesso' }));

    expect(await screen.findByText('Acesso criado. Entre com o seu e-mail e a senha nova.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(chamadas.find((c) => c.chave === 'POST /api/auth/primeiro-acesso')?.corpo)
      .toEqual({ cpf: '52998224725', email: 'ana@academia.com' });
    expect(chamadas.find((c) => c.chave === 'POST /api/auth/primeiro-acesso/senha')?.corpo)
      .toEqual({ cpf: '52998224725', email: 'ana@academia.com', codigo: '123456', senha: 'senha-do-aluno-123' });
  });

  it('o e-mail digitado no login já vem preenchido', async () => {
    api(ligado);
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');

    await userEvent.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));

    expect(screen.getByLabelText('E-mail')).toHaveValue('ana@academia.com');
  });

  it('só envia com os 11 números do CPF e um e-mail', async () => {
    api(ligado);
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));
    const enviar = screen.getByRole('button', { name: 'Enviar código' });

    await userEvent.type(screen.getByLabelText('CPF'), '529.982.247-2');
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    expect(enviar).toBeDisabled();
    await userEvent.type(screen.getByLabelText('CPF'), '5');
    expect(enviar).toBeEnabled();
    await userEvent.clear(screen.getByLabelText('E-mail'));
    await userEvent.type(screen.getByLabelText('E-mail'), 'ana.academia.com');
    expect(enviar).toBeDisabled();
  });

  it('o CPF não aceita letras', async () => {
    api(ligado);
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));

    await userEvent.type(screen.getByLabelText('CPF'), '529a.982');

    expect(screen.getByLabelText('CPF')).toHaveValue('529.982');
  });

  it('só cria com 6 dígitos, a senha com 12 caracteres e a confirmação igual', async () => {
    api({ ...ligado, ...pedido });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await abrirEPedir();
    const criar = await screen.findByRole('button', { name: 'Criar o acesso' });

    await preencherSenha('12345', 'curta', '');
    expect(screen.getByText(/pelo menos 12 caracteres/)).toBeInTheDocument();
    expect(criar).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Código'), '6');
    await userEvent.type(screen.getByLabelText(/^Senha/), '-agora-longa');
    // Sem a confirmação, ainda não.
    expect(criar).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Repita a senha'), 'diferente-da-outra');
    expect(screen.getByText(/não é igual/)).toBeInTheDocument();
    expect(criar).toBeDisabled();
    await userEvent.clear(screen.getByLabelText('Repita a senha'));
    await userEvent.type(screen.getByLabelText('Repita a senha'), 'curta-agora-longa');
    expect(criar).toBeEnabled();
    await userEvent.clear(screen.getByLabelText('Código'));
    await userEvent.type(screen.getByLabelText('Código'), '12345');
    expect(criar).toBeDisabled();
  });

  it('código errado: mostra o erro da API e fica', async () => {
    api({ ...ligado, ...pedido,
      'POST /api/auth/primeiro-acesso/senha': () => json(400, { detail: 'Código inválido ou vencido. Confira o e-mail mais recente ou peça outro código.' }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await abrirEPedir();
    await preencherSenha('000000');

    await userEvent.click(screen.getByRole('button', { name: 'Criar o acesso' }));

    expect(await screen.findByText(/Código inválido ou vencido/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Criar a sua senha' })).toBeInTheDocument();
  });

  it('muitas tentativas no pedido: mostra a explicação da API e não avança', async () => {
    api({ ...ligado, 'POST /api/auth/primeiro-acesso': () => json(429, { detail: 'Muitas tentativas. Tente em 1 minuto.' }) });
    render(<TelaDeLogin aoEntrar={vi.fn()} />);

    await abrirEPedir();

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente em 1 minuto.');
    expect(screen.getByRole('heading', { name: 'Primeiro acesso' })).toBeInTheDocument();
  });

  it('voltar leva ao login sem o aviso', async () => {
    api(ligado);
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));

    await userEvent.click(screen.getByRole('button', { name: 'Voltar para o login' }));

    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
