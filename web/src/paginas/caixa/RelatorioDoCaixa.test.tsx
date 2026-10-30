import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { RelatorioDoCaixa } from './RelatorioDoCaixa';

const total = (recebido: number, estornado = 0) => ({ total: { recebido, estornado, liquido: recebido - estornado } });

describe('relatório do caixa', () => {
  it('cada dia com a diferença do último fechamento, e o total', async () => {
    const api = apiFalsa({ 'GET /api/caixas/relatorio': json(200, { de: '2026-10-01', ate: '2026-10-23',
      dias: [{ dia: '2026-10-22', situacao: 'FECHADO', troco: 50, diferencaNaAbertura: null, movimento: total(300, 100),
        fechamentos: [{ diferenca: 5, justificativa: 'x' }, { diferenca: -10, justificativa: 'troco a mais' }] },
      { dia: '2026-10-23', situacao: 'ABERTO', troco: 50, diferencaNaAbertura: -20, movimento: total(80), fechamentos: [] }],
      total: total(380, 100), diferencaDosFechados: -10, diferencaDasAberturas: -20 }) });
    renderizar(<RelatorioDoCaixa />, { perfil: 'ADMIN' });

    await userEvent.click(screen.getByRole('button', { name: 'Gerar' }));

    expect(await screen.findByText('22/10/2026')).toBeInTheDocument();
    // A diferença do dia (com a justificativa ao passar o mouse) e a soma no total.
    const [doDia, doTotal] = screen.getAllByRole('cell', { name: /-R\$\s10,00/ });
    expect(doDia).toHaveAttribute('title', 'troco a mais');
    expect(doTotal.closest('tfoot')).not.toBeNull();
    expect(screen.getByText('aberto')).toBeInTheDocument();
    // A diferença na abertura (troco − o que ficou no fechamento anterior), no dia e no total.
    const [aberturaDoDia, aberturaNoTotal] = screen.getAllByRole('cell', { name: /-R\$\s20,00/ });
    expect(aberturaDoDia).toHaveClass('erro');
    expect(aberturaNoTotal.closest('tfoot')).not.toBeNull();
    expect(screen.getByRole('cell', { name: /R\$\s280,00/ })).toBeInTheDocument();
    expect(api.de('GET /api/caixas/relatorio')[0].busca.get('de')).toMatch(/-01$/);
  });

  it('período grande demais: a API explica', async () => {
    apiFalsa({ 'GET /api/caixas/relatorio': json(400, { detail: 'o período pode ter no máximo 366 dias' }) });
    renderizar(<RelatorioDoCaixa />, { perfil: 'ADMIN' });

    await userEvent.click(screen.getByRole('button', { name: 'Gerar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('366 dias');
  });
});
