import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ContextoDoUsuario } from '../../sessao/contexto';
import { usuarioDe } from '../../testes/ajudantes';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CobrancasDoAluno } from './CobrancasDoAluno';
import type { Cobranca } from './tipos';

const cobranca = (mudancas: Partial<Cobranca> = {}): Cobranca => ({
  id: 'c1', alunoId: 'a1', tipo: 'MENSALIDADE', parcela: 2, valor: 100, vencimento: '2026-10-01', prazo: '2026-10-01',
  situacao: 'ATRASADA', diasDeAtraso: 5, devidoHoje: { valor: 100, acrescimo: 2, juros: 0.17, total: 102.17 },
  pagamento: null, cancelamento: null, ...mudancas,
});
const paga = cobranca({ id: 'c2', situacao: 'PAGA', diasDeAtraso: 0, devidoHoje: null,
  pagamento: { total: 100, acrescimo: 0, juros: 0, meio: 'PIX', em: '2026-10-02T15:00:00Z' } });

function abrir(lista: Cobranca[], rotas: Parameters<typeof apiFalsa>[0] = {}, perfil: 'RECEPCAO' | 'ADMIN' = 'RECEPCAO') {
  const api = apiFalsa({ 'GET /api/cobrancas': json(200, lista), ...rotas });
  renderizar(<CobrancasDoAluno alunoId="a1" atualizar={0} />, { perfil });
  return api;
}

describe('cobranças do aluno', () => {
  it('atrasada: dias de atraso e o devido de hoje com acréscimo e juros', async () => {
    abrir([cobranca()]);

    expect(await screen.findByText('Mensalidade 2')).toBeInTheDocument();
    expect(screen.getByText(/5 dias de atraso/)).toBeInTheDocument();
    expect(screen.getByText(/acréscimo R\$\s2,00, juros R\$\s0,17/)).toBeInTheDocument();
  });

  it('receber manda o valor exato de hoje e a forma, e recarrega', async () => {
    const api = abrir([cobranca()], { 'POST /api/cobrancas/c1/pagar': json(200, paga) });
    await userEvent.click(await screen.findByRole('button', { name: 'Receber' }));

    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'PIX');
    await userEvent.click(screen.getByRole('button', { name: /Receber R\$\s102,17/ }));

    expect(api.de('POST /api/cobrancas/c1/pagar')[0].corpo).toEqual({ valor: 102.17, meioDePagamento: 'PIX' });
    await screen.findByRole('button', { name: 'Receber' });
    expect(api.de('GET /api/cobrancas')).toHaveLength(2);
  });

  it('sem caixa aberto: mostra o motivo e o caminho para abrir', async () => {
    abrir([cobranca()], { 'POST /api/cobrancas/c1/pagar': json(409,
      { detail: 'O caixa de hoje não foi aberto: abra-o, com o troco, antes de receber ou estornar' }) });
    await userEvent.click(await screen.findByRole('button', { name: 'Receber' }));
    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'DINHEIRO');
    await userEvent.click(screen.getByRole('button', { name: /Receber R\$/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('O caixa de hoje não foi aberto');
    expect(screen.getByRole('link', { name: 'Abrir o caixa de hoje' })).toHaveAttribute('href', '/caixa');
  });

  it('recepção não estorna nem cancela; paga não se recebe de novo', async () => {
    abrir([cobranca(), paga]);
    await screen.findAllByText('Mensalidade 2');

    expect(screen.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Receber' })).toHaveLength(1);
    expect(screen.getByText(/Paga em .*R\$\s100,00 \(Pix\)/)).toBeInTheDocument();
  });

  it('ADMIN estorna a paga e cancela a em aberto, com motivo', async () => {
    const api = abrir([cobranca(), paga], {
      'POST /api/cobrancas/c2/estornar': json(200, cobranca({ id: 'c2' })),
      'POST /api/cobrancas/c1/cancelar': json(200, cobranca({ situacao: 'CANCELADA' })),
    }, 'ADMIN');

    await userEvent.click(await screen.findByRole('button', { name: 'Estornar' }));
    expect(screen.getByText(/O dinheiro sai do caixa de hoje/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Motivo'), 'pagou duas vezes');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar estorno' }));
    expect(api.de('POST /api/cobrancas/c2/estornar')[0].corpo).toEqual({ motivo: 'pagou duas vezes' });

    await userEvent.click(await screen.findByRole('button', { name: 'Cancelar' }));
    await userEvent.type(screen.getByLabelText('Motivo'), 'cortesia');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }));
    expect(api.de('POST /api/cobrancas/c1/cancelar')[0].corpo).toEqual({ motivo: 'cortesia' });
  });

  it('histórico mostra pagamentos e estornos', async () => {
    abrir([paga], { 'GET /api/cobrancas/c2/lancamentos': json(200, [
      { id: 'l1', tipo: 'PAGAMENTO', total: 100, meio: 'PIX', em: '2026-10-02T15:00:00Z', motivo: null },
      { id: 'l2', tipo: 'ESTORNO', total: 100, meio: 'PIX', em: '2026-10-03T15:00:00Z', motivo: 'engano' },
    ]) });

    await userEvent.click(await screen.findByRole('button', { name: 'Histórico' }));

    expect(await screen.findByText(/Pagamento de R\$\s100,00 \(Pix\)/)).toBeInTheDocument();
    expect(screen.getByText(/Estorno de R\$\s100,00 \(Pix\): engano/)).toBeInTheDocument();
  });

  it('quando a matrícula muda (prop "atualizar"), recarrega', async () => {
    const api = apiFalsa({ 'GET /api/cobrancas': json(200, [cobranca()]) });
    const tela = (n: number) => (
      <ContextoDoUsuario.Provider value={usuarioDe('RECEPCAO')}>
        <MemoryRouter><CobrancasDoAluno alunoId="a1" atualizar={n} /></MemoryRouter>
      </ContextoDoUsuario.Provider>
    );
    const { rerender } = render(tela(0));
    await screen.findByText('Mensalidade 2');

    rerender(tela(1));

    await vi.waitFor(() => expect(api.de('GET /api/cobrancas')).toHaveLength(2));
  });
});
