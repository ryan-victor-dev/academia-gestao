import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { ListaDePlanos } from './ListaDePlanos';

const mensal = { id: 'p1', nome: 'Mensal', valorMensal: 120, duracaoMeses: 1, valorTotal: 120, fidelidade: false,
  percentualMulta: null, formaPagamento: 'MENSAL', ativo: true };

describe('planos (ADMIN)', () => {
  it('só o ADMIN vê novo, editar e inativar', async () => {
    apiFalsa({ 'GET /api/planos': json(200, [mensal]) });
    const { unmount } = renderizar(<ListaDePlanos />, { perfil: 'RECEPCAO' });
    await screen.findByText('Mensal');
    expect(screen.queryByRole('button', { name: 'Novo plano' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument();
    unmount();

    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });
    expect(await screen.findByRole('button', { name: 'Novo plano' })).toBeInTheDocument();
  });

  it('novo plano com fidelidade: números como número; sem fidelidade, multa null', async () => {
    const api = apiFalsa({ 'GET /api/planos': json(200, []), 'POST /api/planos': json(201, mensal) });
    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo plano' }));

    await userEvent.type(screen.getByLabelText('Nome'), ' Anual ');
    await userEvent.type(screen.getByLabelText('Valor por mês (R$)'), '99.9');
    await userEvent.clear(screen.getByLabelText('Duração (meses)'));
    await userEvent.type(screen.getByLabelText('Duração (meses)'), '12');
    await userEvent.selectOptions(screen.getByLabelText('Pagamento'), 'A_VISTA');
    await userEvent.click(screen.getByLabelText('Com fidelidade'));
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Multa por sair antes (% do saldo restante)'), '20');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('POST /api/planos')[0].corpo).toEqual({ nome: 'Anual', valorMensal: 99.9, duracaoMeses: 12, fidelidade: true,
      percentualMulta: 20, formaPagamento: 'A_VISTA' });
  });

  it('editar manda a versão; tirar a fidelidade manda multa null', async () => {
    const comFidelidade = { ...mensal, fidelidade: true, percentualMulta: 10 };
    const api = apiFalsa({ 'GET /api/planos': json(200, [comFidelidade]), 'GET /api/planos/p1': json(200, comFidelidade, { ETag: '"5"' }),
      'PUT /api/planos/p1': json(200, mensal) });
    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });

    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));
    await userEvent.click(screen.getByLabelText('Com fidelidade'));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const put = api.de('PUT /api/planos/p1')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"5"');
    expect(put.corpo).toMatchObject({ fidelidade: false, percentualMulta: null });
  });

  it('inativos: pede à API; reativar', async () => {
    const api = apiFalsa({ 'GET /api/planos': (p) => json(200, p.busca.get('incluirInativos') ? [mensal, { ...mensal, id: 'p2', nome: 'Velho', ativo: false }] : [mensal]),
      'POST /api/planos/p2/reativar': json(200, {}) });
    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });
    await screen.findByText('Mensal');

    await userEvent.click(screen.getByLabelText('Mostrar inativos'));
    const velho = (await screen.findByText('Velho')).closest('li')!;
    await userEvent.click(within(velho).getByRole('button', { name: 'Reativar' }));

    expect(api.de('POST /api/planos/p2/reativar')).toHaveLength(1);
  });

  it('excluir: só o ADMIN; confirma, manda o DELETE e recarrega; com matrícula, mostra o motivo', async () => {
    apiFalsa({ 'GET /api/planos': json(200, [mensal]) });
    const { unmount } = renderizar(<ListaDePlanos />, { perfil: 'RECEPCAO' });
    await screen.findByText('Mensal');
    expect(screen.queryByRole('button', { name: 'Excluir' })).not.toBeInTheDocument();
    unmount();

    let excluido = false;
    const api = apiFalsa({ 'GET /api/planos': () => json(200, excluido ? [] : [mensal]),
      'DELETE /api/planos/p1': () => { excluido = true; return new Response(null, { status: 204 }); } });
    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(api.de('DELETE /api/planos/p1')).toHaveLength(1);
    expect(await screen.findByText('Nenhum plano disponível.')).toBeInTheDocument();
  });

  it('excluir plano contratado: mostra o motivo da API', async () => {
    apiFalsa({ 'GET /api/planos': json(200, [mensal]),
      'DELETE /api/planos/p1': json(409, { title: 'Plano com matrículas', detail: 'O plano já foi contratado e não pode ser excluído; inative-o para tirá-lo de oferta' }) });
    renderizar(<ListaDePlanos />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(await screen.findByText(/inative-o para tirá-lo de oferta/)).toBeInTheDocument();
    expect(screen.getByText('Mensal')).toBeInTheDocument();
  });
});
