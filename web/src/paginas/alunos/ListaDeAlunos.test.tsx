import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { ListaDeAlunos } from './ListaDeAlunos';

const pagina = (conteudo: object[], extra: object = {}) =>
  json(200, { conteudo, pagina: 0, tamanho: 20, totalElementos: conteudo.length, totalPaginas: 1, ...extra });
const ana = { id: 'a1', nome: 'Ana Souza', dataNascimento: '1990-05-02', ativo: true };

describe('lista de alunos', () => {
  it('busca pelo que foi digitado e leva ao cadastro', async () => {
    const api = apiFalsa({ 'GET /api/alunos': (p) => pagina(p.busca.get('busca') === 'ana' ? [ana] : []) });
    renderizar(<ListaDeAlunos />, { rota: '/alunos' });

    await userEvent.type(screen.getByLabelText('Buscar por nome ou CPF'), 'ana');

    const link = await screen.findByRole('link', { name: /Ana Souza/ });
    expect(link).toHaveAttribute('href', '/alunos/a1');
    expect(screen.getByText(/^Nascimento: 02\/05\/1990 \(\d+ anos\)$/)).toBeInTheDocument();
    expect(api.de('GET /api/alunos').at(-1)!.busca.get('incluirInativos')).toBe('false');
  });

  it('mostrar inativos: pede à API e marca o inativo', async () => {
    const api = apiFalsa({ 'GET /api/alunos': (p) =>
      pagina(p.busca.get('incluirInativos') === 'true' ? [ana, { ...ana, id: 'a2', nome: 'Bia', ativo: false }] : [ana]) });
    renderizar(<ListaDeAlunos />, { rota: '/alunos' });
    await screen.findByText('Ana Souza');

    await userEvent.click(screen.getByLabelText('Mostrar inativos'));

    expect(await screen.findByText('Inativo')).toBeInTheDocument();
    expect(api.de('GET /api/alunos').at(-1)!.busca.get('incluirInativos')).toBe('true');
  });

  it('paginação pede a página seguinte', async () => {
    const api = apiFalsa({ 'GET /api/alunos': (p) =>
      pagina([ana], { pagina: Number(p.busca.get('pagina')), totalElementos: 30, totalPaginas: 2 }) });
    renderizar(<ListaDeAlunos />, { rota: '/alunos' });

    await userEvent.click(await screen.findByRole('button', { name: 'Próxima' }));

    expect(await screen.findByText('Página 2 de 2')).toBeInTheDocument();
    expect(api.de('GET /api/alunos').at(-1)!.busca.get('pagina')).toBe('1');
  });

  it('recepção cadastra; professor só busca por nome', async () => {
    apiFalsa({ 'GET /api/alunos': pagina([]) });
    const { unmount } = renderizar(<ListaDeAlunos />, { rota: '/alunos' });
    expect(screen.getByRole('link', { name: 'Novo aluno' })).toBeInTheDocument();
    unmount();

    renderizar(<ListaDeAlunos />, { rota: '/alunos', perfil: 'INSTRUTOR' });
    expect(screen.queryByRole('link', { name: 'Novo aluno' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Buscar por nome')).toBeInTheDocument();
  });
});
