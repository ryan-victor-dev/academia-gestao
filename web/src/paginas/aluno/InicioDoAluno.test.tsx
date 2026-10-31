import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { InicioDoAluno } from './InicioDoAluno';

const matricula = (mudancas: object = {}) => ({ id: 'm1', planoNome: 'Trimestral', valorMensal: 99.9, duracaoMeses: 3, valorTotal: 299.7,
  fidelidade: false, percentualMulta: null, formaPagamento: 'MENSAL', inicio: '2026-10-01', fim: '2026-12-31', situacao: 'VIGENTE',
  trancamentos: [], motivoCancelamento: null, multa: null, ...mudancas });
const cobranca = (mudancas: object) => ({ id: 'c', tipo: 'MENSALIDADE', parcela: 1, valor: 99.9, vencimento: '2026-11-01',
  situacao: 'PENDENTE', diasDeAtraso: 0, devidoHoje: null, pagaEm: null, meioDePagamento: null, pagoTotal: null, ...mudancas });

describe('início do aluno', () => {
  it('matrícula vigente, próximo vencimento e aviso de atraso', async () => {
    apiFalsa({ 'GET /api/matriculas/me': json(200, [matricula({ id: 'm0', situacao: 'ENCERRADA' }), matricula()]),
      'GET /api/cobrancas/me': json(200, [cobranca({ id: 'c1', situacao: 'ATRASADA', vencimento: '2026-10-01' }),
        cobranca({ id: 'c3', vencimento: '2026-12-01' }), cobranca({ id: 'c2', vencimento: '2026-11-01' })]) });
    renderizar(<InicioDoAluno />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/Você tem 1 pagamento atrasado/)).toBeInTheDocument();
    expect(screen.getByText(/vale até 31\/12\/2026/)).toBeInTheDocument();
    expect(screen.getByText(/Próximo vencimento: 01\/11\/2026/)).toBeInTheDocument();
  });

  it('sem matrícula: diz para falar com a recepção', async () => {
    apiFalsa({ 'GET /api/matriculas/me': json(200, [matricula({ situacao: 'CANCELADA' })]), 'GET /api/cobrancas/me': json(200, []) });
    renderizar(<InicioDoAluno />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/Nenhuma matrícula vigente/)).toBeInTheDocument();
    expect(screen.queryByText(/atrasado/)).not.toBeInTheDocument();
  });

  it('meus dados: carrega só ao abrir', async () => {
    const api = apiFalsa({ 'GET /api/matriculas/me': json(200, []), 'GET /api/cobrancas/me': json(200, []),
      'GET /api/alunos/me': json(200, { id: 'a1', nome: 'Ana', cpf: '12345678909', email: 'ana@x.com', telefone: '11999990000',
        dataNascimento: '1990-05-02', observacoes: null, ativo: true, criadoEm: '' }) });
    renderizar(<InicioDoAluno />, { perfil: 'ALUNO' });
    await screen.findByText(/Nenhuma matrícula vigente/);
    expect(api.de('GET /api/alunos/me')).toHaveLength(0);

    await userEvent.click(screen.getByText('Meus dados'));

    expect(await screen.findByText('123.456.789-09')).toBeInTheDocument();
    expect(screen.getByText('(11) 99999-0000')).toBeInTheDocument();
    expect(screen.getByText(/^02\/05\/1990 \(\d+ anos\)$/)).toBeInTheDocument();
  });
});
