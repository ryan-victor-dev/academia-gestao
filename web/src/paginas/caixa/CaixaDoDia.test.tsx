import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { hoje } from '../../formato';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CaixaDoDia, type Caixa } from './CaixaDoDia';

const HOJE = hoje();
const caixa = (mudancas: Partial<Caixa> = {}): Caixa => ({
  id: 'x1', dia: HOJE, troco: 50, situacao: 'ABERTO', abertoEm: '2026-10-23T11:00:00Z', dinheiroOculto: true,
  movimento: { formas: [{ forma: 'PIX', recebido: 200, pagamentos: 2, estornado: 0, estornos: 0, liquido: 200 }],
    total: { recebido: 200, estornado: 0, liquido: 200 } },
  fechamentos: [], reaberturas: [], ...mudancas,
});
const fechado = caixa({ situacao: 'FECHADO', dinheiroOculto: false,
  fechamentos: [{ contado: 140, esperado: 150, diferenca: -10, justificativa: 'troco a mais', em: '2026-10-23T22:00:00Z' }] });

describe('caixa do dia', () => {
  it('sem caixa hoje: abre com o troco', async () => {
    const api = apiFalsa({ 'GET /api/caixas/abertos': json(200, []), [`GET /api/caixas/${HOJE}`]: json(404, { detail: 'não aberto' }),
      'POST /api/caixas': json(201, caixa()) });
    renderizar(<CaixaDoDia />);

    await userEvent.type(await screen.findByLabelText('Troco na gaveta (R$)'), '50.5');
    await userEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }));

    expect(api.de('POST /api/caixas')[0].corpo).toEqual({ troco: 50.5 });
    expect(await screen.findByText('Aberto')).toBeInTheDocument();
  });

  it('aberto para a recepção: avisa da contagem às cegas e fecha com o contado', async () => {
    const api = apiFalsa({ 'GET /api/caixas/abertos': json(200, [{ dia: HOJE }]), [`GET /api/caixas/${HOJE}`]: json(200, caixa()),
      [`POST /api/caixas/${HOJE}/fechar`]: json(200, fechado) });
    renderizar(<CaixaDoDia />);

    expect(await screen.findByText(/contagem é às cegas/)).toBeInTheDocument();
    expect(screen.getByText('Total (sem o dinheiro)')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Dinheiro contado (R$)'), '140');
    await userEvent.click(screen.getByRole('button', { name: 'Fechar caixa' }));

    expect(api.de(`POST /api/caixas/${HOJE}/fechar`)[0].corpo).toEqual({ contado: 140, justificativa: null });
    expect(await screen.findByText(/diferença -R\$\s10,00/)).toBeInTheDocument();
    expect(screen.getByText('Justificativa: troco a mais')).toBeInTheDocument();
  });

  it('contado diferente sem justificativa: a API recusa e a tela mostra, sem fechar', async () => {
    apiFalsa({ 'GET /api/caixas/abertos': json(200, [{ dia: HOJE }]), [`GET /api/caixas/${HOJE}`]: json(200, caixa()),
      [`POST /api/caixas/${HOJE}/fechar`]: json(400, { detail: 'o contado difere do esperado: escreva uma justificativa' }) });
    renderizar(<CaixaDoDia />);

    await userEvent.type(await screen.findByLabelText('Dinheiro contado (R$)'), '1');
    await userEvent.click(screen.getByRole('button', { name: 'Fechar caixa' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('escreva uma justificativa');
    expect(screen.getByRole('button', { name: 'Fechar caixa' })).toBeInTheDocument();
  });

  it('dia anterior esquecido aberto: avisa e leva a ele', async () => {
    const api = apiFalsa({ 'GET /api/caixas/abertos': json(200, [{ dia: '2026-01-05' }]),
      [`GET /api/caixas/${HOJE}`]: json(404, {}), 'GET /api/caixas/2026-01-05': json(200, caixa({ dia: '2026-01-05' })) });
    renderizar(<CaixaDoDia />);

    expect(await screen.findByText(/O caixa de 05\/01\/2026 ainda está aberto/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Ver e fechar' }));

    expect(await screen.findByRole('heading', { name: 'Caixa de 05/01/2026' })).toBeInTheDocument();
    expect(api.de('GET /api/caixas/2026-01-05')).toHaveLength(1);
  });

  it('recepção não escolhe o dia nem reabre; ADMIN reabre com motivo', async () => {
    const rotas = { 'GET /api/caixas/abertos': json(200, []), [`GET /api/caixas/${HOJE}`]: json(200, fechado),
      [`POST /api/caixas/${HOJE}/reabrir`]: json(200, caixa({ dinheiroOculto: false })) };
    apiFalsa(rotas);
    const { unmount } = renderizar(<CaixaDoDia />);
    await screen.findByText('Fechado');
    expect(screen.queryByLabelText('Ver o dia')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reabrir o dia' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Relatório do período' })).not.toBeInTheDocument();
    unmount();

    const api = apiFalsa(rotas);
    renderizar(<CaixaDoDia />, { perfil: 'ADMIN' });
    expect(await screen.findByLabelText('Ver o dia')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Relatório do período' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reabrir o dia' }));
    await userEvent.type(screen.getByLabelText('Motivo'), 'contagem errada');
    await userEvent.click(screen.getByRole('button', { name: 'Reabrir' }));

    expect(api.de(`POST /api/caixas/${HOJE}/reabrir`)[0].corpo).toEqual({ motivo: 'contagem errada' });
    expect(await screen.findByText('Aberto')).toBeInTheDocument();
  });

  it('ADMIN num dia passado sem caixa: só avisa (abrir é sempre hoje)', async () => {
    apiFalsa({ 'GET /api/caixas/abertos': json(200, []), [`GET /api/caixas/${HOJE}`]: json(200, fechado),
      'GET /api/caixas/2026-01-05': json(404, {}) });
    renderizar(<CaixaDoDia />, { perfil: 'ADMIN' });
    await screen.findByText('Fechado');

    fireEvent.change(screen.getByLabelText('Ver o dia'), { target: { value: '2026-01-05' } });

    expect(await screen.findByText('Nenhum caixa neste dia.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Abrir caixa' })).not.toBeInTheDocument();
  });
});
