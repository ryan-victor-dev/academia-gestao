import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { vi } from 'vitest';
import type { Perfil, Usuario } from '../sessao/api';
import { ContextoDoUsuario } from '../sessao/contexto';

export const json = (status: number, corpo: unknown, cabecalhos: Record<string, string> = {}) =>
  new Response(corpo === undefined ? null : JSON.stringify(corpo),
    { status, headers: { 'Content-Type': 'application/json', ...cabecalhos } });

export interface Pedido {
  metodo: string;
  caminho: string;
  busca: URLSearchParams;
  corpo: unknown;
  cabecalhos: Headers;
}

type Resposta = Response | ((p: Pedido) => Response);

/**
 * API falsa por rota: "GET /api/alunos" (sem a busca) → resposta. Guarda os pedidos; rota sem
 * resposta dá 404 (e aparece em `pedidos`, para o teste ver o que faltou). A renovação da sessão
 * sempre dá certo.
 */
export function apiFalsa(rotas: Record<string, Resposta>) {
  const pedidos: Pedido[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    const endereco = new URL(url, 'http://teste');
    const pedido: Pedido = {
      metodo: init.method ?? 'GET', caminho: endereco.pathname, busca: endereco.searchParams,
      // Texto é JSON; um arquivo (anexo, ADR 0047) fica como veio.
      corpo: typeof init.body === 'string' ? JSON.parse(init.body) : init.body ?? undefined, cabecalhos: new Headers(init.headers),
    };
    if (pedido.caminho === '/api/auth/renovar') return json(200, { token: 't' });
    pedidos.push(pedido);
    const resposta = rotas[`${pedido.metodo} ${pedido.caminho}`];
    if (!resposta) return json(404, { detail: `sem resposta para ${pedido.metodo} ${pedido.caminho}` });
    return typeof resposta === 'function' ? resposta(pedido) : resposta.clone();
  }));
  return {
    pedidos,
    /** Os pedidos feitos para "MÉTODO /caminho". */
    de: (rota: string) => pedidos.filter((p) => `${p.metodo} ${p.caminho}` === rota),
  };
}

export const usuarioDe = (perfil: Perfil): Usuario => ({
  id: 'u1', nome: 'Fulano', email: 'fulano@academia.com', perfil, ativo: true, precisaTrocarSenha: false,
  somenteLeitura: false,
});

/** Renderiza uma tela como se o usuário do perfil estivesse no endereço, com a rota dada. */
export function renderizar(elemento: ReactNode, { perfil = 'RECEPCAO', rota = '/', endereco = rota }:
  { perfil?: Perfil; rota?: string; endereco?: string } = {}) {
  return render(
    <ContextoDoUsuario.Provider value={usuarioDe(perfil)}>
      <MemoryRouter initialEntries={[endereco]}>
        <Routes>
          <Route path={rota} element={elemento} />
          <Route path="*" element={<p>Fora da tela: {'outra rota'}</p>} />
        </Routes>
      </MemoryRouter>
    </ContextoDoUsuario.Provider>,
  );
}
