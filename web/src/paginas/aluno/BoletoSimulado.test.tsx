import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { BoletoSimulado } from './BoletoSimulado';

const boleto = { id: 'p1', cobrancaId: 'c1', forma: 'BOLETO', total: 102.17, pixCopiaECola: null,
  boletoLinhaDigitavel: '00000000000000000000000000000000000000123456789', boletoUrl: '/boleto-simulado/p1',
  linkDePagamento: null, validoAte: '2026-11-09T03:00:00Z', situacao: 'AGUARDANDO' };

describe('boleto simulado', () => {
  it('mostra o boleto com o aviso de que não vale', async () => {
    const api = apiFalsa({ 'GET /api/pagamentos-online/p1/boleto-simulado': json(200, boleto) });
    renderizar(<BoletoSimulado />, { perfil: 'ALUNO', rota: '/boleto-simulado/:id', endereco: '/boleto-simulado/p1' });

    expect(await screen.findByText(boleto.boletoLinhaDigitavel)).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent('não vale e não deve ser pago');
    expect(screen.getByText('R$ 102,17')).toBeInTheDocument();
    expect(screen.getByText(/^08\/11\/2026,? 23:59$/)).toBeInTheDocument();
    expect(screen.getByText('Aguardando pagamento')).toBeInTheDocument();
    expect(api.de('GET /api/pagamentos-online/p1/boleto-simulado')).toHaveLength(1);
  });

  it('sem o simulado (ou de outro aluno), a recusa da API aparece', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/p1/boleto-simulado': json(404, { detail: 'Simulação desligada' }) });
    renderizar(<BoletoSimulado />, { perfil: 'RECEPCAO', rota: '/boleto-simulado/:id', endereco: '/boleto-simulado/p1' });

    expect(await screen.findByRole('alert')).toHaveTextContent('Simulação desligada');
    expect(screen.queryByText('Linha digitável')).not.toBeInTheDocument();
  });
});
