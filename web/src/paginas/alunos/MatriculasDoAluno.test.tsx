import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { MatriculasDoAluno, type Matricula } from './MatriculasDoAluno';

const matricula = (mudancas: Partial<Matricula> = {}): Matricula => ({
  id: 'm1', planoNome: 'Mensal', valorMensal: 120, duracaoMeses: 1, valorTotal: 120, fidelidade: false,
  percentualMulta: null, formaPagamento: 'MENSAL', inicio: '2026-10-01', fim: '2026-10-31', situacao: 'VIGENTE',
  trancamentos: [], motivoCancelamento: null, multa: null, ...mudancas,
});
const planos = [
  { id: 'p1', nome: 'Mensal', valorMensal: 120, duracaoMeses: 1, ativo: true },
  { id: 'p2', nome: 'Anual', valorMensal: 99.9, duracaoMeses: 12, ativo: true },
  { id: 'p3', nome: 'Antigo', valorMensal: 80, duracaoMeses: 1, ativo: false },
];

function abrir(lista: Matricula[], rotas: Parameters<typeof apiFalsa>[0] = {}) {
  const api = apiFalsa({ 'GET /api/matriculas': json(200, lista), 'GET /api/planos': json(200, planos), ...rotas });
  const aoMudar = vi.fn();
  renderizar(<MatriculasDoAluno alunoId="a1" aoMudar={aoMudar} />);
  return { api, aoMudar };
}

describe('matrículas do aluno', () => {
  it('nova matrícula: só planos ativos; manda aluno, plano e início vazio como null', async () => {
    const { api, aoMudar } = abrir([], { 'POST /api/matriculas': json(201, matricula()) });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova matrícula' }));

    const plano = screen.getByLabelText('Plano');
    expect(within(plano).queryByRole('option', { name: /Antigo/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(plano, 'p2');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('POST /api/matriculas')[0].corpo).toEqual({ alunoId: 'a1', planoId: 'p2', inicio: null });
    await vi.waitFor(() => expect(aoMudar).toHaveBeenCalled());
    expect(api.de('GET /api/matriculas')).toHaveLength(2);
  });

  it('cancelar com fidelidade avisa da multa e manda o motivo', async () => {
    const { api } = abrir([matricula({ fidelidade: true, percentualMulta: 20 })],
      { 'POST /api/matriculas/m1/cancelar': json(200, matricula({ situacao: 'CANCELADA' })) });
    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }));

    expect(screen.getByText(/multa de 20% do saldo restante/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Motivo'), 'mudou de cidade');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }));

    expect(api.de('POST /api/matriculas/m1/cancelar')[0].corpo).toEqual({ motivo: 'mudou de cidade' });
  });

  it('agendada com fidelidade: cancela sem aviso de multa (não começou)', async () => {
    abrir([matricula({ situacao: 'AGENDADA', fidelidade: true, percentualMulta: 20 })]);
    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByText(/saldo restante/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trancar' })).not.toBeInTheDocument();
  });

  it('trancar manda início vazio como null, volta prevista e motivo', async () => {
    const { api } = abrir([matricula()], { 'POST /api/matriculas/m1/trancar': json(200, matricula({ situacao: 'TRANCADA' })) });
    await userEvent.click(await screen.findByRole('button', { name: 'Trancar' }));

    const formulario = screen.getByRole('form', { name: 'Trancar matrícula' });
    await userEvent.type(within(formulario).getByLabelText('Volta prevista'), '2030-01-10');
    await userEvent.type(within(formulario).getByLabelText('Motivo'), 'cirurgia');
    await userEvent.click(within(formulario).getByRole('button', { name: 'Trancar' }));

    expect(api.de('POST /api/matriculas/m1/trancar')[0].corpo)
      .toEqual({ inicio: null, voltaPrevista: '2030-01-10', motivo: 'cirurgia' });
  });

  it('trancada: registrar volta, sem trancar de novo', async () => {
    const { api } = abrir([matricula({ situacao: 'TRANCADA',
      trancamentos: [{ inicio: '2026-10-10', voltaPrevista: '2026-11-10', volta: null, dias: 0, motivo: 'viagem' }] })],
    { 'POST /api/matriculas/m1/registrar-volta': json(200, matricula()) });

    expect(await screen.findByText(/Trancada de 10\/10\/2026 \(volta prevista 10\/11\/2026\): viagem/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Trancar' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Registrar volta' }));

    expect(api.de('POST /api/matriculas/m1/registrar-volta')).toHaveLength(1);
  });

  it('trocar de plano: não oferece o mesmo plano', async () => {
    const { api } = abrir([matricula()], { 'POST /api/matriculas/m1/trocar-plano': json(201, matricula()) });
    await userEvent.click(await screen.findByRole('button', { name: 'Trocar plano' }));

    const seletor = screen.getByLabelText('Novo plano');
    expect(within(seletor).queryByRole('option', { name: /^Mensal/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(seletor, 'p2');
    await userEvent.click(screen.getByRole('button', { name: 'Trocar' }));

    expect(api.de('POST /api/matriculas/m1/trocar-plano')[0].corpo).toEqual({ planoId: 'p2' });
  });

  it('encerrada e cancelada: sem ações; cancelada mostra motivo e multa', async () => {
    abrir([matricula({ id: 'm2', situacao: 'CANCELADA', motivoCancelamento: 'mudou', multa: 50 }),
      matricula({ id: 'm3', situacao: 'ENCERRADA' })]);

    expect(await screen.findByText(/Cancelada: mudou · multa de R\$\s50,00/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar' })).not.toBeInTheDocument();
  });

  it('erro da API na ação aparece e o formulário continua aberto', async () => {
    abrir([matricula()], { 'POST /api/matriculas/m1/trancar': json(409, { detail: 'já existe um trancamento nesse período' }) });
    await userEvent.click(await screen.findByRole('button', { name: 'Trancar' }));
    const formulario = screen.getByRole('form', { name: 'Trancar matrícula' });
    await userEvent.type(within(formulario).getByLabelText('Volta prevista'), '2030-01-10');
    await userEvent.type(within(formulario).getByLabelText('Motivo'), 'x');
    await userEvent.click(within(formulario).getByRole('button', { name: 'Trancar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('já existe um trancamento nesse período');
    expect(screen.getByRole('form', { name: 'Trancar matrícula' })).toBeInTheDocument();
  });
});
