import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatarEspera } from './Espera';
import { TelaDeLogin } from './TelaDeLogin';
import { ConfirmarEmail } from './ConfirmarEmail';

const json = (status: number, corpo: unknown, cabecalhos: Record<string, string> = {}) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/problem+json', ...cabecalhos } });
const muitas = (segundos: string) => () => json(429,
  { title: 'Muitas tentativas', detail: 'Muitas tentativas de login. Tente de novo mais tarde.' }, { 'Retry-After': segundos });

/** fetch falso por "MÉTODO caminho". */
function api(rotas: Record<string, () => Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) =>
    rotas[`${init.method ?? 'GET'} ${url}`]?.() ?? json(404, {})));
}
const ligado = { 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: true, primeiroAcesso: true }) };

describe('formatarEspera', () => {
  it.each([
    [1, '1 s'], [59, '59 s'], [60, '1 min'], [90, '1 min 30 s'], [899, '14 min 59 s'],
    [3600, '1 h'], [3661, '1 h 1 min'], [86399, '23 h 59 min'],
  ])('%i s: %s', (segundos, texto) => {
    expect(formatarEspera(segundos).replace(/\u00a0/g, ' ')).toBe(texto);
  });

  it('o número não se separa da unidade na quebra de linha', () => {
    expect(formatarEspera(3661)).toBe('1\u00a0h 1\u00a0min');
    expect(formatarEspera(44)).toBe('44\u00a0s');
  });
});

describe('espera depois de muitas tentativas (429 com Retry-After)', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });
  const usuario = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const passar = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });

  it('login: mostra quanto falta, conta até zero com o botão desligado, e some no fim', async () => {
    api({ ...ligado, 'POST /api/auth/login': muitas('90') });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.type(screen.getByLabelText('Senha', { exact: true }), 'senha-errada-123');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 1 min 30 s.');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();

    await passar(31_000);
    expect(screen.getByRole('alert')).toHaveTextContent('Tente de novo em 59 s.');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();

    await passar(60_000);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
    // E o relógio da contagem para: nada fica batendo a cada segundo na tela de login.
    expect(vi.getTimerCount()).toBe(0);
  });

  it('login: outra conta (outro e-mail) não espera pela primeira; a primeira continua esperando', async () => {
    api({ ...ligado, 'POST /api/auth/login': muitas('600') });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    const email = screen.getByLabelText('E-mail');
    await u.type(email, 'ana@academia.com');
    await u.type(screen.getByLabelText('Senha', { exact: true }), 'senha-errada-123');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Tente de novo em 10 min.');

    await u.clear(email);
    await u.type(email, 'bia@academia.com');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();

    // Maiúsculas e espaços não fazem outra conta.
    await u.clear(email);
    await u.type(email, ' Ana@Academia.com');
    expect(screen.getByRole('alert')).toHaveTextContent('Tente de novo em 10 min.');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled();
  });

  it('sem o Retry-After, a explicação da API, como antes', async () => {
    api({ ...ligado, 'POST /api/auth/login': () => json(429,
      { title: 'Muitas tentativas', detail: 'Muitas tentativas de login. Tente de novo mais tarde.' }) });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.type(screen.getByLabelText('Senha', { exact: true }), 'senha-errada-123');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas de login. Tente de novo mais tarde.');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
  });

  it('servidor ocupado (503 com Retry-After): a explicação da API, sem contagem', async () => {
    api({ ...ligado, 'POST /api/auth/login': () => json(503,
      { title: 'Serviço ocupado', detail: 'O sistema está ocupado agora. Tente de novo em alguns segundos.' },
      { 'Retry-After': '5' }) });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.type(screen.getByLabelText('Senha', { exact: true }), 'senha-certa-123');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('O sistema está ocupado agora. Tente de novo em alguns segundos.');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
  });

  it('primeiro acesso, ao criar a senha: também espera', async () => {
    api({ ...ligado, 'POST /api/auth/primeiro-acesso': () => new Response(null, { status: 202 }),
      'POST /api/auth/primeiro-acesso/senha': muitas('30') });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));
    await u.type(screen.getByLabelText('CPF'), '529.982.247-25');
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.click(screen.getByRole('button', { name: 'Enviar código' }));
    await u.type(await screen.findByLabelText('Código'), '123456');
    await u.type(screen.getByLabelText(/^Senha/), 'senha-do-aluno-123');
    await u.type(screen.getByLabelText('Repita a senha'), 'senha-do-aluno-123');
    await u.click(screen.getByRole('button', { name: 'Criar o acesso' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 30 s.');
    expect(screen.getByRole('button', { name: 'Criar o acesso' })).toBeDisabled();
  });

  it('primeiro acesso: mostra quanto falta para enviar o código de novo', async () => {
    api({ ...ligado, 'POST /api/auth/primeiro-acesso': muitas('125') });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.click(await screen.findByRole('button', { name: 'Primeiro acesso (aluno)' }));
    await u.type(screen.getByLabelText('CPF'), '529.982.247-25');
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.click(screen.getByRole('button', { name: 'Enviar código' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 2 min 5 s.');
    expect(screen.getByRole('button', { name: 'Enviar código' })).toBeDisabled();
    await passar(125_000);
    expect(screen.getByRole('button', { name: 'Enviar código' })).toBeEnabled();
  });

  it('esqueci a senha: mostra quanto falta', async () => {
    api({ ...ligado, 'POST /api/auth/esqueci-senha': muitas('45') });
    const u = usuario();
    render(<TelaDeLogin aoEntrar={vi.fn()} />);
    await u.click(await screen.findByRole('button', { name: 'Esqueci a senha' }));
    await u.type(screen.getByLabelText('E-mail'), 'ana@academia.com');
    await u.click(screen.getByRole('button', { name: 'Enviar código' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 45 s.');
    expect(screen.getByRole('button', { name: 'Enviar código' })).toBeDisabled();
  });

  it('confirmar o e-mail: muitos códigos pedidos, com o título da API e horas de espera', async () => {
    api({ 'POST /api/auth/renovar': () => json(200, { token: 't' }), 'POST /api/auth/email/codigo': () => json(429,
      { title: 'Muitos códigos pedidos', detail: 'Muitos códigos pedidos. Espere um pouco e peça de novo.' },
      { 'Retry-After': '7300' }) });
    const u = usuario();
    render(<ConfirmarEmail email="ana@academia.com" aoConfirmar={vi.fn()} />);
    await u.click(screen.getByRole('button', { name: 'Enviar código' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitos códigos pedidos. Tente de novo em 2 h 1 min.');
    expect(screen.getByRole('button', { name: 'Enviar código' })).toBeDisabled();
  });
});
