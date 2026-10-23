import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { RevisoesVencidas } from './RevisoesVencidas';

const pagina = (conteudo: object[]) => json(200, { conteudo, pagina: 0, tamanho: 20, totalElementos: conteudo.length, totalPaginas: 1 });

describe('revisões vencidas', () => {
  it('aluno pelo nome, com link e a data da revisão', async () => {
    apiFalsa({ 'GET /api/fichas/revisoes-vencidas': pagina([{ fichaId: 'f1', alunoId: 'a1', alunoNome: 'Caio', revisaoEm: '2026-10-20' }]) });
    renderizar(<RevisoesVencidas />, { perfil: 'INSTRUTOR' });

    const link = await screen.findByRole('link', { name: /Caio/ });
    expect(link).toHaveAttribute('href', '/alunos/a1');
    expect(link).toHaveTextContent('Revisão em 20/10/2026');
  });

  it('nenhuma: diz isso', async () => {
    apiFalsa({ 'GET /api/fichas/revisoes-vencidas': pagina([]) });
    renderizar(<RevisoesVencidas />, { perfil: 'INSTRUTOR' });

    expect(await screen.findByText('Nenhuma ficha com revisão vencida.')).toBeInTheDocument();
  });
});
