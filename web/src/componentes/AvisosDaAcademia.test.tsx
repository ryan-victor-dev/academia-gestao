import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../testes/ajudantes';
import { AvisosDaAcademia } from './AvisosDaAcademia';

describe('avisos da academia (ADR 0047)', () => {
  it('mostra a preferência e desliga e religa', async () => {
    const api = apiFalsa({ 'GET /api/avisos/preferencias': json(200, { recebeAvisosDaAcademia: true }),
      'PUT /api/avisos/preferencias': (p) => json(200, p.corpo) });
    renderizar(<AvisosDaAcademia />, { perfil: 'ALUNO' });

    const caixa = await screen.findByLabelText(/Receber por e-mail e SMS os eventos e comunicados/);
    expect(caixa).toBeChecked();
    await userEvent.click(caixa);
    expect(api.de('PUT /api/avisos/preferencias')[0].corpo).toEqual({ recebeAvisosDaAcademia: false });
    expect(await screen.findByText(/não vai mais receber/)).toBeInTheDocument();
    expect(caixa).not.toBeChecked();
    await userEvent.click(caixa);
    expect(api.de('PUT /api/avisos/preferencias')[1].corpo).toEqual({ recebeAvisosDaAcademia: true });
    expect(await screen.findByText('Você vai receber os avisos.')).toBeInTheDocument();
    expect(screen.getByText(/lembretes de fatura/)).toBeInTheDocument();
  });

  it('só de leitura: não muda', async () => {
    apiFalsa({ 'GET /api/avisos/preferencias': json(200, { recebeAvisosDaAcademia: false }) });
    renderizar(<AvisosDaAcademia somenteLeitura />, { perfil: 'ALUNO' });
    const caixa = await screen.findByLabelText(/Receber por e-mail e SMS/);
    expect(caixa).not.toBeChecked();
    expect(caixa).toBeDisabled();
  });

  it('a recusa da API aparece', async () => {
    apiFalsa({ 'GET /api/avisos/preferencias': json(200, { recebeAvisosDaAcademia: true }),
      'PUT /api/avisos/preferencias': json(403, { detail: 'Sem permissão' }) });
    renderizar(<AvisosDaAcademia />, { perfil: 'ALUNO' });
    const caixa = await screen.findByLabelText(/Receber por e-mail e SMS/);
    await userEvent.click(caixa);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(caixa).toBeChecked();
  });
});
