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
  pagamento: { total: 100, acrescimo: 0, juros: 0, meio: 'PIX', codigoTransacao: 'E2E9', em: '2026-10-02T15:00:00Z' } });

const verHistorico = async () => userEvent.click(await screen.findByRole('button', { name: /Ver histórico de pagamentos/ }));

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

  it('receber manda o valor exato de hoje, a forma e o código da transação, e recarrega', async () => {
    const api = abrir([cobranca()], { 'POST /api/cobrancas/c1/pagar': json(200, paga) });
    await userEvent.click(await screen.findByRole('button', { name: 'Receber' }));

    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'PIX');
    expect(screen.getByText(/ID da transação no comprovante do Pix/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Código da transação'), 'E2E123abc');
    await userEvent.click(screen.getByRole('button', { name: /Receber R\$\s102,17/ }));

    expect(api.de('POST /api/cobrancas/c1/pagar')[0].corpo)
      .toEqual({ valor: 102.17, meioDePagamento: 'PIX', codigoTransacao: 'E2E123abc' });
    await screen.findByRole('button', { name: 'Receber' });
    expect(api.de('GET /api/cobrancas')).toHaveLength(2);
    expect(screen.getByRole('status')).toHaveTextContent('Mensalidade 2 recebida: está no histórico de pagamentos.');
  });

  it('no cartão, o código é obrigatório; no dinheiro, o campo nem aparece', async () => {
    const api = abrir([cobranca()], { 'POST /api/cobrancas/c1/pagar': json(200, paga) });
    await userEvent.click(await screen.findByRole('button', { name: 'Receber' }));

    expect(screen.queryByLabelText('Código da transação')).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'CARTAO_CREDITO');
    expect(screen.getByLabelText('Código da transação')).toBeRequired();
    expect(screen.getByLabelText('Código da transação')).toHaveAttribute('maxLength', '100');
    expect(screen.getByText(/NSU ou o código de autorização/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Código da transação'), 'abc');
    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'DINHEIRO');
    expect(screen.queryByLabelText('Código da transação')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Receber R\$\s102,17/ }));

    expect(api.de('POST /api/cobrancas/c1/pagar')[0].corpo).toEqual({ valor: 102.17, meioDePagamento: 'DINHEIRO' });
  });

  it('código já usado: mostra o motivo da API', async () => {
    abrir([cobranca()], { 'POST /api/cobrancas/c1/pagar': json(409,
      { title: 'Código da transação já usado', detail: 'Este código de Pix já pagou outra cobrança: confira o comprovante' }) });
    await userEvent.click(await screen.findByRole('button', { name: 'Receber' }));
    await userEvent.selectOptions(screen.getByLabelText('Forma de pagamento'), 'PIX');
    await userEvent.type(screen.getByLabelText('Código da transação'), 'E2E1');
    await userEvent.click(screen.getByRole('button', { name: /Receber R\$/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Este código de Pix já pagou outra cobrança');
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
    await verHistorico();

    expect(screen.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Receber' })).toHaveLength(1);
    expect(screen.getByText(/Paga em .*R\$\s100,00 \(Pix, código E2E9\)/)).toBeInTheDocument();
  });

  it('ADMIN estorna a paga e cancela a em aberto, com motivo', async () => {
    const api = abrir([cobranca(), paga], {
      'POST /api/cobrancas/c2/estornar': json(200, cobranca({ id: 'c2' })),
      'POST /api/cobrancas/c1/cancelar': json(200, cobranca({ situacao: 'CANCELADA' })),
    }, 'ADMIN');

    await verHistorico();
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
      { id: 'l1', tipo: 'PAGAMENTO', total: 100, meio: 'PIX', codigoTransacao: 'E2E9', em: '2026-10-02T15:00:00Z', motivo: null },
      { id: 'l2', tipo: 'ESTORNO', total: 100, meio: 'PIX', codigoTransacao: 'E2E9', em: '2026-10-03T15:00:00Z', motivo: 'engano' },
      { id: 'l3', tipo: 'PAGAMENTO', total: 100, meio: 'DINHEIRO', codigoTransacao: null, em: '2026-10-04T15:00:00Z', motivo: null },
    ]) });

    await verHistorico();
    await userEvent.click(await screen.findByRole('button', { name: 'Histórico' }));

    expect(await screen.findByText(/Pagamento de R\$\s100,00 \(Pix, código E2E9\)/)).toBeInTheDocument();
    expect(screen.getByText(/Estorno de R\$\s100,00 \(Pix, código E2E9\): engano/)).toBeInTheDocument();
    expect(screen.getByText(/Pagamento de R\$\s100,00 \(Dinheiro\)$/)).toBeInTheDocument();
  });

  it('pagas e canceladas ficam escondidas até pedir o histórico de pagamentos', async () => {
    const cancelada = cobranca({ id: 'c3', parcela: 3, situacao: 'CANCELADA', diasDeAtraso: 0, devidoHoje: null,
      cancelamento: { em: '2026-10-03T15:00:00Z', motivo: 'cortesia' } });
    abrir([paga, cobranca(), cancelada]);
    await screen.findByRole('button', { name: 'Receber' });

    expect(screen.getAllByText(/^Mensalidade/)).toHaveLength(1);
    expect(screen.queryByText(/Paga em/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Cancelada: cortesia/)).not.toBeInTheDocument();
    const botao = screen.getByRole('button', { name: 'Ver histórico de pagamentos (2)' });
    expect(botao).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(botao);
    expect(screen.getByRole('heading', { name: 'Histórico de pagamentos' })).toBeInTheDocument();
    expect(screen.getByText(/Paga em .*R\$\s100,00/)).toBeInTheDocument();
    expect(screen.getByText('Cancelada: cortesia')).toBeInTheDocument();
    expect(botao).toHaveAttribute('aria-expanded', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'Esconder histórico de pagamentos' }));
    expect(screen.queryByText(/Paga em/)).not.toBeInTheDocument();
  });

  it('tudo pago: diz que não há nada em aberto e mostra o botão', async () => {
    abrir([paga]);
    expect(await screen.findByText('Nenhuma cobrança em aberto.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ver histórico de pagamentos (1)' })).toBeInTheDocument();
  });

  it('sem cobrança nenhuma: nem o botão do histórico', async () => {
    abrir([]);
    expect(await screen.findByText('Nenhuma cobrança.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /histórico de pagamentos/ })).not.toBeInTheDocument();
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

  const boleto = { id: 'p1', cobrancaId: 'c1', forma: 'BOLETO', total: 102.17, pixCopiaECola: null,
    boletoLinhaDigitavel: '34191.79001 01043.510047 91020.150008 1 99990000010217', boletoUrl: 'https://banco.exemplo/b.pdf',
    linkDePagamento: null, validoAte: '2026-10-09T03:00:00Z', situacao: 'AGUARDANDO' };

  it('boleto no balcão (ADR 0047): gera para a cobrança em aberto e mostra a linha digitável', async () => {
    const api = abrir([cobranca(), paga], { 'GET /api/pagamentos-online/balcao': json(200, { boleto: true }),
      'POST /api/pagamentos-online/balcao/boleto': json(201, boleto) });
    await verHistorico();
    await screen.findByText(/Paga em/);
    const botoes = await screen.findAllByRole('button', { name: 'Gerar boleto' });
    expect(botoes).toHaveLength(1);

    await userEvent.click(botoes[0]);
    expect(await screen.findByLabelText('Linha digitável do boleto')).toHaveValue(boleto.boletoLinhaDigitavel);
    expect(api.de('POST /api/pagamentos-online/balcao/boleto')[0].corpo).toEqual({ alunoId: 'a1', cobrancaId: 'c1' });
    expect(screen.getByRole('link', { name: 'Abrir o boleto' })).toHaveAttribute('href', boleto.boletoUrl);
    expect(screen.getByText(/Passe ao aluno: ele paga até 08\/10\/2026/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gerar boleto' })).not.toBeInTheDocument();
  });

  it('sem boleto disponível, o botão não aparece; recusa da API aparece', async () => {
    abrir([cobranca()], { 'GET /api/pagamentos-online/balcao': json(200, { boleto: false }) });
    await screen.findByText('Mensalidade 2');
    await screen.findByRole('button', { name: 'Receber' });
    expect(screen.queryByRole('button', { name: 'Gerar boleto' })).not.toBeInTheDocument();
  });

  it('boleto recusado pela API: mostra o motivo', async () => {
    abrir([cobranca()], { 'GET /api/pagamentos-online/balcao': json(200, { boleto: true }),
      'POST /api/pagamentos-online/balcao/boleto': json(409, { detail: 'O boleto não está disponível' }) });
    await userEvent.click(await screen.findByRole('button', { name: 'Gerar boleto' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('O boleto não está disponível');
  });
});
