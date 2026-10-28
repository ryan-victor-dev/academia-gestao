import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ESPERAS_DAS_OPCOES, TelaDeLogin } from './TelaDeLogin';

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

  describe('as opções falharam', () => {
    beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
    afterEach(() => vi.useRealTimers());

    /** Responde com cada resposta da lista, em ordem; depois, a última. */
    const emOrdem = (...respostas: Array<() => Response>) => {
      let i = 0;
      return () => respostas[Math.min(i++, respostas.length - 1)]();
    };
    const perguntas = (chamadas: Array<{ chave: string }>) =>
      chamadas.filter((c) => c.chave === 'GET /api/auth/opcoes').length;

    it('uma vez (servidor subindo): pergunta de novo e oferece', async () => {
      const chamadas = api({ 'GET /api/auth/opcoes': emOrdem(() => json(500, {}), () => json(200, { esqueciSenha: true })) });
      render(<TelaDeLogin aoEntrar={vi.fn()} />);

      await vi.waitFor(() => expect(perguntas(chamadas)).toBe(1));
      expect(screen.queryByRole('button', { name: 'Esqueci a senha' })).not.toBeInTheDocument();
      // Espera antes de perguntar de novo (a folga cobre o tempo real que corre junto).
      await vi.advanceTimersByTimeAsync(ESPERAS_DAS_OPCOES[0] - 500);
      expect(perguntas(chamadas)).toBe(1);
      await vi.advanceTimersByTimeAsync(500);

      expect(await screen.findByRole('button', { name: 'Esqueci a senha' })).toBeInTheDocument();
      expect(perguntas(chamadas)).toBe(2);
      expect(screen.queryByText(/Não foi possível conferir/)).not.toBeInTheDocument();
    });

    it('sem conexão até o fim das tentativas: avisa, não oferece, e tenta de novo quando pedem', async () => {
      // Depois do aviso, a resposta fica pendurada até o teste soltá-la.
      let conecta = false;
      let soltar: (r: Response) => void = () => {};
      const chamadas: string[] = [];
      vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        chamadas.push(url);
        if (!conecta) throw new TypeError('Failed to fetch');
        return new Promise<Response>((r) => { soltar = r; });
      }));
      render(<TelaDeLogin aoEntrar={vi.fn()} />);

      for (const espera of ESPERAS_DAS_OPCOES) await vi.advanceTimersByTimeAsync(espera);
      expect(await screen.findByText(/Não foi possível conferir/)).toBeInTheDocument();
      expect(chamadas).toHaveLength(ESPERAS_DAS_OPCOES.length + 1);
      await vi.advanceTimersByTimeAsync(60_000);
      expect(chamadas).toHaveLength(ESPERAS_DAS_OPCOES.length + 1);
      expect(screen.queryByRole('button', { name: 'Esqueci a senha' })).not.toBeInTheDocument();

      conecta = true;
      await userEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));
      // Enquanto pergunta, o aviso velho some.
      await vi.waitFor(() => expect(chamadas).toHaveLength(ESPERAS_DAS_OPCOES.length + 2));
      expect(screen.queryByText(/Não foi possível conferir/)).not.toBeInTheDocument();
      soltar(json(200, { esqueciSenha: true }));

      expect(await screen.findByRole('button', { name: 'Esqueci a senha' })).toBeInTheDocument();
      expect(screen.queryByText(/Não foi possível conferir/)).not.toBeInTheDocument();
    });

    it('saiu da tela durante a espera: não pergunta mais', async () => {
      const chamadas = api({ 'GET /api/auth/opcoes': () => json(503, {}) });
      const { unmount } = render(<TelaDeLogin aoEntrar={vi.fn()} />);
      await vi.waitFor(() => expect(perguntas(chamadas)).toBe(1));
      await vi.advanceTimersByTimeAsync(100); // a resposta chega e a espera começa

      unmount();
      for (const espera of ESPERAS_DAS_OPCOES) await vi.advanceTimersByTimeAsync(espera);

      expect(perguntas(chamadas)).toBe(1);
    });

    it('desligado não é falha: nem pergunta de novo nem avisa', async () => {
      const chamadas = api({ 'GET /api/auth/opcoes': () => json(200, { esqueciSenha: false }) });
      render(<TelaDeLogin aoEntrar={vi.fn()} />);

      await vi.waitFor(() => expect(perguntas(chamadas)).toBe(1));
      for (const espera of ESPERAS_DAS_OPCOES) await vi.advanceTimersByTimeAsync(espera);

      expect(perguntas(chamadas)).toBe(1);
      expect(screen.queryByText(/Não foi possível conferir/)).not.toBeInTheDocument();
    });
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
