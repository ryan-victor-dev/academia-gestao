import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { diaSeguinte, hoje } from '../../formato';
import { ErroDaApi } from '../../sessao/api';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { EditorDeFicha, rotularCampoDaFicha } from './EditorDeFicha';
import type { Ficha } from './tipos';

const catalogo = [
  { id: 'e1', nome: 'Supino', grupoMuscular: 'PEITO', observacao: null, ativo: true },
  { id: 'e2', nome: 'Agachamento', grupoMuscular: 'QUADRICEPS', observacao: null, ativo: true },
];
const ficha: Ficha = {
  id: 'f1', alunoId: 'a1', atual: true, montadaEm: '2026-10-01T12:00:00Z', revisaoEm: null, corrigidaEm: null, encerradaEm: null,
  divisoes: [{ nome: 'A', exercicios: [
    { exercicioId: 'e1', nome: 'Supino', series: 4, repeticoes: '8-12', carga: '20 kg', descansoSegundos: 90, observacao: null },
    { exercicioId: 'velho', nome: 'Crucifixo', series: 3, repeticoes: '12', carga: null, descansoSegundos: null, observacao: null },
  ] }],
};

function abrir(props: Partial<Parameters<typeof EditorDeFicha>[0]> = {}) {
  apiFalsa({ 'GET /api/exercicios': json(200, catalogo) });
  type Corpo = { revisaoEm: string | null; divisoes: Array<{ nome: string; exercicios: Array<{ exercicioId: string }> }> };
  const aoSalvar = vi.fn(async (_corpo: Corpo) => undefined);
  renderizar(<EditorDeFicha titulo="Ficha nova" aoSalvar={aoSalvar} aoCancelar={vi.fn()} {...props} />);
  return aoSalvar;
}
const item = (n: number, divisao = 'A') => screen.getByRole('group', { name: `Exercício ${n} da divisão ${divisao}` });

describe('editor de ficha', () => {
  it('ficha nova: duas divisões; números como número e vazio como null', async () => {
    const aoSalvar = abrir();
    await screen.findAllByRole('option', { name: 'Supino (Peito)' });

    await userEvent.selectOptions(within(item(1)).getByLabelText('Exercício'), 'e1');
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar divisão' }));
    await userEvent.selectOptions(within(item(1, 'B')).getByLabelText('Exercício'), 'e2');
    await userEvent.clear(within(item(1, 'B')).getByLabelText('Descanso (s)'));
    await userEvent.type(within(item(1, 'B')).getByLabelText('Carga'), '40 kg');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(aoSalvar).toHaveBeenCalledWith({ revisaoEm: null, divisoes: [
      { nome: 'A', exercicios: [{ exercicioId: 'e1', series: 3, repeticoes: '10', carga: null, descansoSegundos: 60, observacao: null }] },
      { nome: 'B', exercicios: [{ exercicioId: 'e2', series: 3, repeticoes: '10', carga: '40 kg', descansoSegundos: null, observacao: null }] },
    ] });
  });

  it('sem exercício escolhido não salva', async () => {
    abrir();
    await screen.findAllByRole('option', { name: 'Supino (Peito)' });

    expect(screen.getByRole('button', { name: 'Salvar ficha' })).toBeDisabled();
  });

  it('ficha nova a partir da atual: o exercício que saiu do catálogo fica para escolher de novo', async () => {
    abrir({ base: ficha });
    await screen.findAllByRole('option', { name: 'Supino (Peito)' });

    expect(within(item(1)).getByLabelText('Exercício')).toHaveValue('e1');
    expect(within(item(2)).getByLabelText('Exercício')).toHaveValue('');
    expect(screen.queryByRole('option', { name: /Crucifixo/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salvar ficha' })).toBeDisabled();
  });

  it('correção: mantém o exercício da ficha que saiu do catálogo', async () => {
    const aoSalvar = abrir({ base: ficha, correcao: true });
    await screen.findAllByRole('option', { name: 'Crucifixo (inativo)' });

    expect(within(item(2)).getByLabelText('Exercício')).toHaveValue('velho');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));
    expect(aoSalvar).toHaveBeenCalledWith(expect.objectContaining({ divisoes: [expect.objectContaining({
      exercicios: [expect.objectContaining({ exercicioId: 'e1', series: 4, carga: '20 kg', descansoSegundos: 90 }),
        expect.objectContaining({ exercicioId: 'velho' })] })] }));
  });

  it('subir, descer e remover mudam a ordem', async () => {
    const aoSalvar = abrir({ base: ficha, correcao: true });
    await screen.findAllByRole('option', { name: 'Crucifixo (inativo)' });

    await userEvent.click(within(item(2)).getByRole('button', { name: 'Subir' }));
    expect(within(item(1)).getByLabelText('Exercício')).toHaveValue('velho');
    await userEvent.click(within(item(1)).getByRole('button', { name: 'Descer' }));
    expect(within(item(1)).getByLabelText('Exercício')).toHaveValue('e1');
    await userEvent.click(within(item(2)).getByRole('button', { name: 'Remover exercício' }));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(aoSalvar.mock.calls[0][0].divisoes[0].exercicios.map((e) => e.exercicioId)).toEqual(['e1']);
  });

  it('erro de um campo da ficha aparece com a divisão e o exercício', async () => {
    const aoSalvar = vi.fn(async () => {
      throw new ErroDaApi(400, 'Um ou mais campos estão inválidos.', { 'divisoes[0].exercicios[0].series': 'deve ser de 1 a 20' });
    });
    abrir({ aoSalvar });
    await screen.findAllByRole('option', { name: 'Supino (Peito)' });
    await userEvent.selectOptions(within(item(1)).getByLabelText('Exercício'), 'e1');

    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(await screen.findByText('Divisão 1, exercício 1, séries: deve ser de 1 a 20')).toBeInTheDocument();
  });

  it('não oferece mais divisões que o limite (7)', async () => {
    abrir();
    await screen.findAllByRole('option', { name: 'Supino (Peito)' });
    for (let i = 0; i < 6; i++) await userEvent.click(screen.getByRole('button', { name: 'Adicionar divisão' }));

    expect(screen.getAllByRole('group', { name: /^Exercício 1 da divisão/ })).toHaveLength(7);
    expect(screen.queryByRole('button', { name: 'Adicionar divisão' })).not.toBeInTheDocument();
  });

  it('rótulo de campo composto', () => {
    expect(rotularCampoDaFicha('divisoes[1].nome')).toBe('Divisão 2, nome');
    expect(rotularCampoDaFicha('revisaoEm')).toBe('revisão');
  });

  it('revisão: a partir de amanhã na ficha nova; do dia seguinte à montagem na correção', async () => {
    abrir();
    expect(screen.getByLabelText('Revisão em (opcional, até 1 ano)')).toHaveAttribute('min', diaSeguinte(hoje()));
  });

  it('revisão na correção: do dia seguinte ao da montagem', async () => {
    abrir({ base: ficha, correcao: true });
    expect(screen.getByLabelText('Revisão em (opcional, até 1 ano)')).toHaveAttribute('min', '2026-10-02');
  });

  it('ficha nova a partir da atual não copia a data de revisão', async () => {
    abrir({ base: { ...ficha, revisaoEm: '2026-10-20' } });
    expect(screen.getByLabelText('Revisão em (opcional, até 1 ano)')).toHaveValue('');
  });

  it('a correção mantém a data de revisão', async () => {
    abrir({ base: { ...ficha, revisaoEm: '2026-12-20' }, correcao: true });
    expect(screen.getByLabelText('Revisão em (opcional, até 1 ano)')).toHaveValue('2026-12-20');
  });
});
