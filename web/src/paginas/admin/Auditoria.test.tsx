import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { Auditoria } from './Auditoria';

const pagina = (conteudo: object[]) => json(200, { conteudo, pagina: 0, tamanho: 20, totalElementos: conteudo.length, totalPaginas: 1 });

describe('auditoria', () => {
  it('alterações: quem, o quê, antes e depois; filtro por entidade', async () => {
    const api = apiFalsa({ 'GET /api/auditoria': pagina([{ id: 1, momento: '2026-10-23T15:00:00Z', autor: 'u2', autorNome: 'Rita',
      entidade: 'ALUNO', entidadeId: 'a1', acao: 'ALTERADO', alteracoes: { telefone: { antes: '1199', depois: '1188' } } }]) });
    renderizar(<Auditoria />, { perfil: 'ADMIN' });

    expect(await screen.findByText('Aluno: alterado')).toBeInTheDocument();
    expect(screen.getByText(/Por Rita/)).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '1199' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '1188' })).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('O quê'), 'CAIXA');
    expect(api.de('GET /api/auditoria').at(-1)!.busca.get('entidade')).toBe('CAIXA');
  });

  it('autor nulo é o sistema', async () => {
    apiFalsa({ 'GET /api/auditoria': pagina([{ id: 1, momento: '2026-10-23T15:00:00Z', autor: null, autorNome: null,
      entidade: 'USUARIO', entidadeId: 'u1', acao: 'CRIADO', alteracoes: {} }]) });
    renderizar(<Auditoria />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Por o sistema/)).toBeInTheDocument();
  });

  it('acessos: tipo e conta vão à API; o tipo aparece por extenso', async () => {
    const api = apiFalsa({ 'GET /api/auditoria': pagina([]), 'GET /api/auditoria/acessos': pagina([
      { id: 9, momento: '2026-10-23T15:00:00Z', tipo: 'LOGIN_FALHO', usuarioNome: null, conta: 'x@y.com', ip: '10.0.0.1', alunoId: null }]) });
    renderizar(<Auditoria />, { perfil: 'ADMIN' });
    await userEvent.click(screen.getByRole('tab', { name: 'Acessos' }));

    expect(await screen.findByText('Senha errada', { selector: '.principal' })).toBeInTheDocument();
    expect(screen.getByText('Senha errada', { selector: '.principal' }).closest('li')).toHaveTextContent('IP 10.0.0.1');
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'LOGIN');
    await userEvent.type(screen.getByLabelText('E-mail digitado no login (exato)'), ' x@y.com ');

    await screen.findByText('Senha errada', { selector: '.principal' });
    await new Promise((r) => setTimeout(r, 400));
    const ultimo = api.de('GET /api/auditoria/acessos').at(-1)!;
    expect(ultimo.busca.get('tipo')).toBe('LOGIN');
    expect(ultimo.busca.get('conta')).toBe('x@y.com');
  });

  it('acessos: a abertura do relatório de idades e gênero aparece por extenso e filtra (ADR 0048)', async () => {
    const api = apiFalsa({ 'GET /api/auditoria': pagina([]), 'GET /api/auditoria/acessos': pagina([
      { id: 10, momento: '2026-10-30T15:00:00Z', tipo: 'RELATORIO_PERFIL', usuarioNome: 'Admin', conta: null, ip: '10.0.0.2', alunoId: null }]) });
    renderizar(<Auditoria />, { perfil: 'ADMIN' });
    await userEvent.click(screen.getByRole('tab', { name: 'Acessos' }));

    expect(await screen.findByText('Abriu o relatório de idades e gênero', { selector: '.principal' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'RELATORIO_PERFIL');
    await vi.waitFor(() => expect(api.de('GET /api/auditoria/acessos').at(-1)!.busca.get('tipo')).toBe('RELATORIO_PERFIL'));
  });
});
