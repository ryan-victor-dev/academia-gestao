import { describe, expect, it, vi } from 'vitest';
import { chamar, chamarComVersao, entrar, ErroDaApi, renovar, sair, trocarSenha } from './api';

type Chamada = { url: string; init: RequestInit };

/** fetch falso: responde na ordem dada e guarda cada chamada. */
function servidor(...respostas: Array<Response | (() => Response)>) {
  const chamadas: Chamada[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    chamadas.push({ url, init });
    const proxima = respostas.shift();
    if (!proxima) throw new Error(`chamada inesperada: ${url}`);
    return typeof proxima === 'function' ? proxima() : proxima;
  }));
  return chamadas;
}

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } });
const token = (valor: string) =>
  json(200, { token: valor, expiraEm: '', precisaTrocarSenha: false, sessaoExpiraEm: '' });
const autorizacao = (c: Chamada) => new Headers(c.init.headers).get('Authorization');

describe('sessão', () => {
  it('entrar guarda o token e as chamadas o mandam no cabeçalho', async () => {
    const chamadas = servidor(token('t1'), json(200, { nome: 'Ana' }));

    await entrar('ana@academia.com', 'senha');
    await chamar('/api/me');

    expect(chamadas[0].url).toBe('/api/auth/login');
    expect(autorizacao(chamadas[1])).toBe('Bearer t1');
  });

  it('token vencido: renova uma vez e repete a chamada', async () => {
    const chamadas = servidor(token('velho'), json(401, {}), token('novo'), json(200, { ok: true }));
    await entrar('ana@academia.com', 'senha');

    await expect(chamar('/api/me')).resolves.toEqual({ ok: true });

    expect(chamadas.map((c) => c.url)).toEqual(['/api/auth/login', '/api/me', '/api/auth/renovar', '/api/me']);
    expect(autorizacao(chamadas[3])).toBe('Bearer novo');
  });

  it('renovar nunca manda o token no cabeçalho (a sessão é o cookie)', async () => {
    const chamadas = servidor(token('t1'), token('t2'));
    await entrar('ana@academia.com', 'senha');

    await renovar();

    expect(chamadas[1].url).toBe('/api/auth/renovar');
    expect(autorizacao(chamadas[1])).toBeNull();
  });

  it('várias chamadas com 401 ao mesmo tempo dividem uma renovação só', async () => {
    let liberar!: () => void;
    const segura = new Promise<void>((r) => (liberar = r));
    const chamadas: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
      chamadas.push(url);
      if (url === '/api/auth/renovar') {
        await segura;
        return token('novo');
      }
      return new Headers(init.headers).get('Authorization') === 'Bearer novo' ? json(200, {}) : json(401, {});
    }));

    const tres = Promise.all([chamar('/api/a'), chamar('/api/b'), chamar('/api/c')]);
    await vi.waitFor(() => expect(chamadas.filter((u) => u === '/api/auth/renovar')).toHaveLength(1));
    liberar();
    await tres;

    // Uma no começo (sem token) e nenhuma outra: as três esperaram a mesma.
    expect(chamadas.filter((u) => u === '/api/auth/renovar')).toHaveLength(1);
  });

  it('sessão encerrada: a chamada falha com 401 e o token é esquecido', async () => {
    const chamadas = servidor(token('t1'), json(401, {}), json(401, {}), json(401, {}));
    await entrar('ana@academia.com', 'senha');

    await expect(chamar('/api/me')).rejects.toMatchObject({ status: 401 });
    // Sem token, a próxima chamada tenta renovar antes, e não manda um token velho.
    await expect(chamar('/api/me')).rejects.toMatchObject({ status: 401 });
    expect(chamadas.map((c) => c.url)).toEqual(['/api/auth/login', '/api/me', '/api/auth/renovar', '/api/auth/renovar']);
  });

  it('sair avisa o servidor e esquece o token', async () => {
    const chamadas = servidor(token('t1'), new Response(null, { status: 204 }), json(401, {}));
    await entrar('ana@academia.com', 'senha');

    await sair();
    await expect(chamar('/api/me')).rejects.toBeInstanceOf(ErroDaApi);

    expect(chamadas.map((c) => c.url)).toEqual(['/api/auth/login', '/api/auth/sair', '/api/auth/renovar']);
  });

  it('erro da API chega com a mensagem e os campos do ProblemDetail', async () => {
    servidor(token('t1'), json(400, { detail: 'Dados inválidos', erros: [{ campo: 'cpf', mensagem: 'inválido' }] }));
    await entrar('ana@academia.com', 'senha');

    await expect(chamar('/api/alunos', { metodo: 'POST', corpo: {} }))
      .rejects.toMatchObject({ status: 400, message: 'Dados inválidos', erros: { cpf: 'inválido' } });
  });

  it('sem rede: erro com status 0 e mensagem clara', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));

    await expect(entrar('ana@academia.com', 'senha')).rejects.toMatchObject({ status: 0 });
  });

  it('trocar a senha passa a usar o token novo que a API devolve', async () => {
    const chamadas = servidor(token('temporario'), token('definitivo'), json(200, {}));
    await entrar('ana@academia.com', 'senha');

    await trocarSenha('temporaria-123', 'minha-senha-nova-1');
    await chamar('/api/me');

    expect(autorizacao(chamadas[1])).toBe('Bearer temporario');
    expect(autorizacao(chamadas[2])).toBe('Bearer definitivo');
  });

  it('versão: lê o ETag e manda no If-Match', async () => {
    const chamadas = servidor(token('t1'),
      new Response('{"nome":"Ana"}', { status: 200, headers: { ETag: '"3"' } }), json(200, {}));
    await entrar('ana@academia.com', 'senha');

    const { dados, versao } = await chamarComVersao<{ nome: string }>('/api/alunos/1');
    await chamar('/api/alunos/1', { metodo: 'PUT', corpo: dados, versao: versao! });

    expect(versao).toBe('"3"');
    expect(new Headers(chamadas[2].init.headers).get('If-Match')).toBe('"3"');
  });
});
