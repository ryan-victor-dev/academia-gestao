import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { ListaDePlanos } from './ListaDePlanos';

describe('planos', () => {
  it('mensal, à vista e fidelidade explicados', async () => {
    apiFalsa({ 'GET /api/planos': json(200, [
      { id: 'p1', nome: 'Mensal', valorMensal: 120, duracaoMeses: 1, valorTotal: 120, fidelidade: false, percentualMulta: null, formaPagamento: 'MENSAL' },
      { id: 'p2', nome: 'Anual', valorMensal: 90, duracaoMeses: 12, valorTotal: 1080, fidelidade: true, percentualMulta: 20, formaPagamento: 'A_VISTA' },
    ]) });
    renderizar(<ListaDePlanos />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/1 mês · pagamento mensal/)).toBeInTheDocument();
    expect(screen.getByText(/12 meses · à vista: R\$\s1\.080,00/)).toBeInTheDocument();
    expect(screen.getByText(/multa de 20% do saldo restante/)).toBeInTheDocument();
  });
});
