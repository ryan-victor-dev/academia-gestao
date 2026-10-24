import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CartaoSimulado } from './CartaoSimulado';

const naPagina = () => renderizar(<CartaoSimulado />, { perfil: 'ALUNO', rota: '/cartao-simulado/:id', endereco: '/cartao-simulado/k1' });

describe('página de cartão simulada (ADR 0037)', () => {
  it('avisa que é teste e não pede cartão nenhum', () => {
    apiFalsa({});
    naPagina();

    expect(screen.getByText(/Nenhum cartão é pedido nem cobrado/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('aprovar simula o pagamento deste e volta aos pagamentos', async () => {
    const api = apiFalsa({ 'POST /api/pagamentos-online/me/k1/simular-pagamento': () => new Response(null, { status: 204 }) });
    naPagina();

    await userEvent.click(screen.getByRole('button', { name: 'Aprovar pagamento (teste)' }));

    expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/me/k1/simular-pagamento')).toHaveLength(1);
  });

  it('erro (não é o simulado, ou o pagamento é de outro aluno): mostra e fica', async () => {
    apiFalsa({ 'POST /api/pagamentos-online/me/k1/simular-pagamento': json(404, { detail: 'Simulação desligada' }) });
    naPagina();

    await userEvent.click(screen.getByRole('button', { name: 'Aprovar pagamento (teste)' }));

    expect(await screen.findByText('Simulação desligada')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Aprovar pagamento (teste)' })).toBeEnabled();
  });

  it('voltar sem pagar não chama nada', async () => {
    const api = apiFalsa({});
    naPagina();

    await userEvent.click(screen.getByRole('button', { name: 'Voltar sem pagar' }));

    expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/me/k1/simular-pagamento')).toHaveLength(0);
  });
});
