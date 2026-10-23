import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { MeuTreino } from './MeuTreino';

const item = (nome: string) => ({ exercicioId: nome, nome, series: 3, repeticoes: '12', carga: null, descansoSegundos: 60, observacao: null });
const ficha = (mudancas: object = {}) => ({ id: 'f1', atual: true, montadaEm: '2026-10-01T12:00:00Z', revisaoEm: '2026-12-01',
  corrigidaEm: null, encerradaEm: null,
  divisoes: [{ nome: 'A', exercicios: [item('Supino')] }, { nome: 'B', exercicios: [item('Agachamento')] }], ...mudancas });
const pagina = (conteudo: object[]) => json(200, { conteudo, pagina: 0, tamanho: 20, totalElementos: conteudo.length, totalPaginas: 1 });

describe('meu treino', () => {
  it('uma divisão por vez; a aba troca', async () => {
    apiFalsa({ 'GET /api/fichas/me': pagina([ficha()]) });
    renderizar(<MeuTreino />, { perfil: 'ALUNO' });

    expect(await screen.findByText('Supino')).toBeInTheDocument();
    expect(screen.queryByText('Agachamento')).not.toBeInTheDocument();
    expect(screen.getByText(/revisão em 01\/12\/2026/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'B' }));

    expect(screen.getByText('Agachamento')).toBeInTheDocument();
    expect(screen.queryByText('Supino')).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'B' })).toHaveAttribute('aria-selected', 'true');
  });

  it('sem ficha: avisa; as anteriores ficam abaixo', async () => {
    apiFalsa({ 'GET /api/fichas/me': pagina([ficha({ id: 'f0', atual: false, encerradaEm: '2026-10-01T12:00:00Z' })]) });
    renderizar(<MeuTreino />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/Você ainda não tem ficha/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Fichas anteriores' })).toBeInTheDocument();
  });
});
