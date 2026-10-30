import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { LancamentosDoDia, type LancamentoDoDia } from './LancamentosDoDia';

const lancamento = (mudancas: Partial<LancamentoDoDia>): LancamentoDoDia => ({
  id: 'l1', tipo: 'PAGAMENTO', em: '2026-10-30T13:05:00Z', forma: 'PIX', total: 102.5, codigoTransacao: 'E2E9',
  alunoId: 'a1', alunoNome: 'Ana Aluna', porNome: 'Rita', motivo: null, ...mudancas,
});

describe('pagamentos e estornos do dia (ADMIN)', () => {
  it('cada um com a forma, o valor, o código, o aluno e quem registrou', async () => {
    apiFalsa({ 'GET /api/caixas/2026-10-30/lancamentos': json(200, [
      lancamento({}),
      lancamento({ id: 'l2', forma: 'DINHEIRO', total: 100, codigoTransacao: null, alunoId: 'a2', alunoNome: null, porNome: null }),
      lancamento({ id: 'l3', tipo: 'ESTORNO', motivo: 'pagou duas vezes', porNome: 'Admin' }),
    ]) });
    renderizar(<LancamentosDoDia dia="2026-10-30" versao={1} />, { perfil: 'ADMIN' });

    const linhas = await screen.findAllByRole('row');
    expect(linhas).toHaveLength(4);
    // Só a hora (a lista é de um dia), no fuso da academia: 13:05 UTC são 10:05 em São Paulo.
    expect(linhas[1]).toHaveTextContent(/^10:05R\$\s102,50PixE2E9Ana AlunaRita$/);
    expect(linhas[2]).toHaveTextContent(/R\$\s100,00Dinheiro—abrir o aluno—$/);
    expect(linhas[3]).toHaveTextContent(/Estorno de R\$\s102,50Pixpagou duas vezesE2E9.*Admin/);
    expect(screen.getAllByRole('link', { name: 'Ana Aluna' })[0]).toHaveAttribute('href', '/alunos/a1');
    expect(screen.getByRole('link', { name: 'abrir o aluno' })).toHaveAttribute('href', '/alunos/a2');
  });

  it('dia sem lançamentos: avisa', async () => {
    apiFalsa({ 'GET /api/caixas/2026-10-30/lancamentos': json(200, []) });
    renderizar(<LancamentosDoDia dia="2026-10-30" versao={1} />, { perfil: 'ADMIN' });

    expect(await screen.findByText('Nenhum pagamento nem estorno neste dia.')).toBeInTheDocument();
  });

  it('erro da API: mostra, sem quebrar a tela', async () => {
    apiFalsa({ 'GET /api/caixas/2026-10-30/lancamentos': json(500, { detail: 'falhou' }) });
    renderizar(<LancamentosDoDia dia="2026-10-30" versao={1} />, { perfil: 'ADMIN' });

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
