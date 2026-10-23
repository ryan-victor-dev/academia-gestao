import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { TreinoDoAluno } from './TreinoDoAluno';
import type { Ficha } from './tipos';

const ficha = (mudancas: Partial<Ficha> = {}): Ficha => ({
  id: 'f1', alunoId: 'a1', atual: true, montadaEm: '2026-10-01T12:00:00Z', revisaoEm: '2026-11-01', revisaoVencida: false,
  corrigidaEm: null, encerradaEm: null,
  divisoes: [{ nome: 'A', exercicios: [{ exercicioId: 'e1', nome: 'Supino', series: 4, repeticoes: '8-12', carga: '20 kg',
    descansoSegundos: 90, observacao: 'Cotovelos a 45°' }] }],
  ...mudancas,
});
const catalogo = [{ id: 'e1', nome: 'Supino', grupoMuscular: 'PEITO', observacao: null, ativo: true }];

describe('treino do aluno', () => {
  it('mostra a ficha atual com cada exercício', async () => {
    apiFalsa({ 'GET /api/fichas/atual': json(200, ficha({ revisaoVencida: true })) });
    renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo />, { perfil: 'INSTRUTOR' });

    expect(await screen.findByRole('heading', { name: 'Treino A' })).toBeInTheDocument();
    expect(screen.getByText(/4 × 8-12/)).toBeInTheDocument();
    expect(screen.getByText('Cotovelos a 45°')).toBeInTheDocument();
    expect(screen.getByText(/A data de revisão chegou/)).toBeInTheDocument();
  });

  it('sem ficha: o professor monta; o corpo leva o aluno', async () => {
    const api = apiFalsa({ 'GET /api/fichas/atual': json(404, {}), 'GET /api/exercicios': json(200, catalogo),
      'POST /api/fichas': json(201, ficha()) });
    renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo />, { perfil: 'INSTRUTOR' });

    expect(await screen.findByText('Nenhuma ficha montada.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Montar ficha nova' }));
    await screen.findByRole('option', { name: 'Supino (Peito)' });
    await userEvent.selectOptions(screen.getByLabelText('Exercício'), 'e1');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(api.de('POST /api/fichas')[0].corpo).toMatchObject({ alunoId: 'a1', revisaoEm: null });
    await screen.findByRole('button', { name: 'Montar ficha nova' });
    expect(api.de('GET /api/fichas/atual')).toHaveLength(2);
  });

  it('corrigir manda a versão lida; 412 explicado', async () => {
    const api = apiFalsa({ 'GET /api/fichas/atual': json(200, ficha(), { ETag: '"7"' }), 'GET /api/exercicios': json(200, catalogo),
      'PUT /api/fichas/f1': json(412, { detail: 'versão' }) });
    renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo />, { perfil: 'ADMIN' });

    await userEvent.click(await screen.findByRole('button', { name: 'Corrigir' }));
    await screen.findByRole('option', { name: 'Supino (Peito)' });
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(api.de('PUT /api/fichas/f1')[0].cabecalhos.get('If-Match')).toBe('"7"');
    expect(await screen.findByRole('alert')).toHaveTextContent('Outra pessoa corrigiu esta ficha');
  });

  it('recepção só consulta; aluno inativo não ganha ficha nova', async () => {
    apiFalsa({ 'GET /api/fichas/atual': json(200, ficha()) });
    const { unmount } = renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo />, { perfil: 'RECEPCAO' });
    await screen.findByRole('heading', { name: 'Treino A' });
    expect(screen.queryByRole('button', { name: 'Montar ficha nova' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Corrigir' })).not.toBeInTheDocument();
    unmount();

    renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo={false} />, { perfil: 'INSTRUTOR' });
    await screen.findByRole('heading', { name: 'Treino A' });
    expect(screen.queryByRole('button', { name: 'Montar ficha nova' })).not.toBeInTheDocument();
  });

  it('fichas anteriores: só as encerradas', async () => {
    apiFalsa({ 'GET /api/fichas/atual': json(200, ficha()), 'GET /api/fichas': json(200, {
      conteudo: [ficha(), ficha({ id: 'f0', atual: false, montadaEm: '2026-08-01T12:00:00Z', encerradaEm: '2026-10-01T12:00:00Z' })],
      pagina: 0, tamanho: 20, totalElementos: 2, totalPaginas: 1 }) });
    renderizar(<TreinoDoAluno alunoId="a1" alunoAtivo />, { perfil: 'INSTRUTOR' });

    await userEvent.click(await screen.findByRole('button', { name: 'Fichas anteriores' }));

    expect(await screen.findAllByText(/^Montada em /, { selector: 'summary' })).toHaveLength(1);
  });
});
