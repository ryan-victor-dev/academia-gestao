import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { MeusPagamentos } from './MeusPagamentos';

const base = { tipo: 'MENSALIDADE', valor: 100, diasDeAtraso: 0, devidoHoje: null, pagaEm: null, meioDePagamento: null, pagoTotal: null };

describe('meus pagamentos', () => {
  it('em aberto com o valor de hoje e o detalhe; pagas no histórico', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, [
      { ...base, id: 'c1', parcela: 1, vencimento: '2026-10-01', situacao: 'ATRASADA', diasDeAtraso: 3,
        devidoHoje: { valor: 100, acrescimo: 2, juros: 0.1, total: 102.1 } },
      { ...base, id: 'c2', parcela: 2, vencimento: '2026-11-01', situacao: 'PENDENTE', devidoHoje: { valor: 100, acrescimo: 0, juros: 0, total: 100 } },
      { ...base, id: 'c0', parcela: 0, vencimento: '2026-09-01', situacao: 'PAGA', pagaEm: '2026-09-01T15:00:00Z', meioDePagamento: 'PIX', pagoTotal: 100 },
    ]) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/3 dias de atraso/)).toBeInTheDocument();
    expect(screen.getByText(/R\$\s102,10/)).toBeInTheDocument();
    expect(screen.getByText(/acréscimo R\$\s2,00 \+ juros R\$\s0,10/)).toBeInTheDocument();
    expect(screen.getByText(/Paga em .* R\$\s100,00 \(Pix\)/)).toBeInTheDocument();
    // Só as duas em aberto ficam na seção "Em aberto"; a paga vai só para o histórico.
    expect(document.querySelectorAll('.cartoes .cartao-item')).toHaveLength(2);
  });

  it('nada em aberto: diz isso', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, []) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText('Nada em aberto.')).toBeInTheDocument();
  });
});
