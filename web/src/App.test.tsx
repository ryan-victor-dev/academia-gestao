import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const json = (status: number, corpo: unknown) =>
  new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } });
const usuario = (mudancas: object = {}) => ({ id: '1', nome: 'Ana', email: 'ana@academia.com', perfil: 'RECEPCAO',
  ativo: true, precisaTrocarSenha: false, somenteLeitura: false, ...mudancas });

/** API falsa: sessão válida e /api/me devolvendo cada usuário da lista, na ordem. */
function comSessao(...eus: object[]) {
  const fetch = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === '/api/auth/renovar' || url === '/api/auth/trocar-senha') return json(200, { token: 't' });
    if (url === '/api/auth/sair') return new Response(null, { status: 204 });
    if (url === '/api/auth/email/codigo' || url === '/api/auth/email/confirmar') return new Response(null, { status: 204 });
    if (url === '/api/me') return json(200, eus.length > 1 ? eus.shift() : eus[0]);
    return json(404, {});
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

describe('App', () => {
  beforeEach(() => window.history.pushState({}, '', '/'));

  it('com a sessão do cookie, abre já dentro, com o menu do perfil', async () => {
    comSessao(usuario());

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument();
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    expect(within(menu).getByRole('link', { name: 'Caixa' })).toBeInTheDocument();
    expect(within(menu).queryByRole('link', { name: 'Exercícios' })).not.toBeInTheDocument();
  });

  it('sem sessão, mostra o login', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, {})));

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
  });

  it('sair volta ao login e encerra a sessão no servidor', async () => {
    const fetch = comSessao(usuario());
    render(<App />);

    await userEvent.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(fetch.mock.calls.map((c) => c[0])).toContain('/api/auth/sair');
  });

  it('quem entra pela tela de login começa no início, e não no endereço de quem saiu', async () => {
    // A recepção sai no /caixa, que não existe para o instrutor: ele não pode cair em "não encontrada".
    window.history.pushState({}, '', '/caixa');
    let logado = false;
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/auth/renovar') return json(401, {});
      if (url === '/api/auth/opcoes') return json(200, { esqueciSenha: false, primeiroAcesso: false });
      if (url === '/api/auth/login') { logado = true; return json(200, { token: 't' }); }
      if (url === '/api/me' && logado) return json(200, usuario({ nome: 'Bia', perfil: 'INSTRUTOR' }));
      return json(404, {});
    }));
    render(<App />);

    await userEvent.type(await screen.findByLabelText('E-mail'), 'bia@academia.com');
    await userEvent.type(screen.getByLabelText('Senha', { exact: true }), 'senha-da-bia-1');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));

    expect(await screen.findByRole('heading', { name: 'Olá, Bia' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/');
  });

  it('senha temporária: só a troca de senha, sem menu; trocada, entra', async () => {
    comSessao(usuario({ precisaTrocarSenha: true }), usuario());
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Crie sua senha' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Senha atual'), 'temporaria-123');
    await userEvent.type(screen.getByLabelText(/Nova senha/), 'minha-senha-nova-1');
    await userEvent.type(screen.getByLabelText('Repita a nova senha'), 'minha-senha-nova-1');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar nova senha' }));

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument();
  });

  it('e-mail a confirmar (ADR 0038): só a confirmação, sem menu; confirmado, entra', async () => {
    const fetch = comSessao(usuario({ precisaConfirmarEmail: true }), usuario());
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Confirme seu e-mail' })).toBeInTheDocument();
    expect(screen.getByText('ana@academia.com')).toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Enviar código' }));
    await userEvent.type(await screen.findByLabelText('Código'), '123456');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(await screen.findByRole('heading', { name: 'Olá, Ana' })).toBeInTheDocument();
    const urls = fetch.mock.calls.map((c) => c[0]);
    // Confirmado, renova o token (sem a restrição) antes de carregar o usuário de novo.
    // Depois do /api/me, a página inicial busca as notícias (ADR 0047): o que importa é a ordem até ele.
    const confirmar = urls.indexOf('/api/auth/email/confirmar');
    expect(urls.slice(confirmar, confirmar + 3)).toEqual(['/api/auth/email/confirmar', '/api/auth/renovar', '/api/me']);
  });

  it('aluno inativo: avisa que é só consulta', async () => {
    comSessao(usuario({ perfil: 'ALUNO', somenteLeitura: true }));

    render(<App />);

    expect(await screen.findByText(/você pode consultar seus dados, mas não alterar nada/)).toBeInTheDocument();
  });

  it('endereço de outro perfil: não encontrada', async () => {
    window.history.pushState({}, '', '/equipe');
    comSessao(usuario());

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument();
  });

  it('endereço do próprio perfil abre direto (link salvo, recarregar)', async () => {
    window.history.pushState({}, '', '/senha');
    comSessao(usuario());

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Trocar minha senha' })).toBeInTheDocument();
  });

  it('aluno no endereço do cadastro de um aluno: não encontrada, sem chamar a API dos alunos', async () => {
    window.history.pushState({}, '', '/alunos/00000000-0000-0000-0000-000000000001');
    const fetch = comSessao(usuario({ perfil: 'ALUNO' }));

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument();
    expect(fetch.mock.calls.map((c) => c[0]).filter((u) => u.startsWith('/api/alunos'))).toEqual([]);
  });

  it('aluno abre no início dele (matrícula e pagamentos), e não nos atalhos da equipe', async () => {
    comSessao(usuario({ perfil: 'ALUNO' }));

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Minha matrícula' })).toBeInTheDocument();
  });

  it('as telas do ADMIN existem de verdade (não "em breve")', async () => {
    window.history.pushState({}, '', '/equipe');
    comSessao(usuario({ perfil: 'ADMIN' }));

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Novo login' })).toBeInTheDocument();
    expect(screen.queryByText(/chega numa próxima versão/)).not.toBeInTheDocument();
  });
});
