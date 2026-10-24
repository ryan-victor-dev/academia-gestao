import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { desenhoDoQr } from '../../componentes/QrCode';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { MeusPagamentos, paginaExterna } from './MeusPagamentos';

const desligado = { ligado: false, simulado: false, pix: false, boleto: false, pagaveis: [] };
const atrasada = { tipo: 'MENSALIDADE', valor: 100, pagaEm: null, meioDePagamento: null, pagoTotal: null, id: 'c1', parcela: 1,
  vencimento: '2026-10-01', situacao: 'ATRASADA', diasDeAtraso: 3, devidoHoje: { valor: 100, acrescimo: 2, juros: 0.1, total: 102.1 } };
const futura = { ...atrasada, id: 'c3', parcela: 3, vencimento: '2026-12-01', situacao: 'PENDENTE', diasDeAtraso: 0,
  devidoHoje: { valor: 100, acrescimo: 0, juros: 0, total: 100 } };
const pix = { id: 'p1', cobrancaId: 'c1', forma: 'PIX', total: 102.1, pixCopiaECola: 'PIX-COPIA-E-COLA', boletoLinhaDigitavel: null,
  boletoUrl: null, validoAte: '2026-10-05T03:00:00Z', situacao: 'AGUARDANDO' };
const ligado = (extra = {}) => ({ ligado: true, simulado: false, pix: true, boleto: true,
  pagaveis: [{ cobrancaId: 'c1', total: 102.1, pix: null, boleto: null }], ...extra });

const base = { tipo: 'MENSALIDADE', valor: 100, diasDeAtraso: 0, devidoHoje: null, pagaEm: null, meioDePagamento: null, pagoTotal: null };

describe('meus pagamentos', () => {
  it('em aberto com o valor de hoje e o detalhe; pagas no histórico', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/me': json(200, desligado), 'GET /api/cobrancas/me': json(200, [
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

  it('histórico: o cartão pelo app com o nome certo (ADR 0037)', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/me': json(200, desligado), 'GET /api/cobrancas/me': json(200, [
      { ...base, id: 'c1', parcela: 1, vencimento: '2026-09-01', situacao: 'PAGA', pagaEm: '2026-09-01T15:00:00Z', meioDePagamento: 'CREDITO_ONLINE', pagoTotal: 100 },
      { ...base, id: 'c2', parcela: 2, vencimento: '2026-10-01', situacao: 'PAGA', pagaEm: '2026-10-01T15:00:00Z', meioDePagamento: 'DEBITO_ONLINE', pagoTotal: 100 },
    ]) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/\(Crédito pelo app\)/)).toBeInTheDocument();
    expect(screen.getByText(/\(Débito pelo app\)/)).toBeInTheDocument();
  });

  describe('cartão (ADR 0037)', () => {
    afterEach(() => vi.restoreAllMocks());
    const comCartao = (extra = {}) => ligado({ pix: false, boleto: false, credito: true, debito: true, ...extra });
    const cartao = (forma: string, link: string) => ({ ...pix, id: 'k1', forma, pixCopiaECola: null, linkDePagamento: link });

    it('só com cartão já conta como ligado; os dois botões aparecem', async () => {
      apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, comCartao()) });
      renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

      expect(await screen.findByRole('button', { name: 'Cartão de crédito' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Cartão de débito' })).toBeInTheDocument();
      expect(screen.getByText(/Pague pelo app, com Pix, boleto ou cartão/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Pagar com Pix' })).not.toBeInTheDocument();
    });

    it.each([['CREDITO', 'Cartão de crédito', 'Cartão de débito'], ['DEBITO', 'Cartão de débito', 'Cartão de crédito']])(
      'só %s ligado: só o botão dele', async (forma, aparece, some) => {
        apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]),
          'GET /api/pagamentos-online/me': json(200, comCartao({ credito: forma === 'CREDITO', debito: forma === 'DEBITO' })) });
        renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

        expect(await screen.findByRole('button', { name: aparece })).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: some })).not.toBeInTheDocument();
      });

    it('crédito: gera e vai à página do simulado, dentro do app', async () => {
      const api = apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, comCartao()),
        'POST /api/pagamentos-online/me': json(201, cartao('CREDITO', '/cartao-simulado/k1')) });
      const externa = vi.spyOn(paginaExterna, 'abrir').mockImplementation(() => {});
      renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

      await userEvent.click(await screen.findByRole('button', { name: 'Cartão de crédito' }));

      expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
      expect(api.de('POST /api/pagamentos-online/me')[0].corpo).toEqual({ cobrancaId: 'c1', forma: 'CREDITO' });
      expect(externa).not.toHaveBeenCalled();
    });

    it('débito numa empresa de verdade: sai do app para a página dela', async () => {
      const api = apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, comCartao()),
        'POST /api/pagamentos-online/me': json(201, cartao('DEBITO', 'https://empresa.exemplo/pagar/k1')) });
      const externa = vi.spyOn(paginaExterna, 'abrir').mockImplementation(() => {});
      renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

      await userEvent.click(await screen.findByRole('button', { name: 'Cartão de débito' }));

      await vi.waitFor(() => expect(externa).toHaveBeenCalledWith('https://empresa.exemplo/pagar/k1'));
      expect(api.de('POST /api/pagamentos-online/me')[0].corpo).toEqual({ cobrancaId: 'c1', forma: 'DEBITO' });
    });

    it('o que ainda vale: vai direto, sem gerar outro', async () => {
      const api = apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]),
        'GET /api/pagamentos-online/me': json(200, comCartao({ pagaveis: [{ cobrancaId: 'c1', total: 102.1, pix: null, boleto: null,
          credito: cartao('CREDITO', 'https://empresa.exemplo/pagar/k1'), debito: null }] })) });
      const externa = vi.spyOn(paginaExterna, 'abrir').mockImplementation(() => {});
      renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

      await userEvent.click(await screen.findByRole('button', { name: 'Cartão de crédito' }));

      expect(externa).toHaveBeenCalledWith('https://empresa.exemplo/pagar/k1');
      expect(api.de('POST /api/pagamentos-online/me')).toHaveLength(0);
    });

    it('erro ao gerar o cartão aparece, e o botão volta', async () => {
      apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, comCartao()),
        'POST /api/pagamentos-online/me': json(409, { detail: 'O cartão de crédito não está disponível no app' }) });
      renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

      await userEvent.click(await screen.findByRole('button', { name: 'Cartão de crédito' }));

      expect(await screen.findByText('O cartão de crédito não está disponível no app')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Cartão de crédito' })).toBeEnabled();
    });
  });

  it('nada em aberto: diz isso', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, []), 'GET /api/pagamentos-online/me': json(200, desligado) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText('Nada em aberto.')).toBeInTheDocument();
  });

  it('desligado: só na recepção, sem botões', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, desligado) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText('O pagamento é feito na recepção.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pagar com Pix' })).not.toBeInTheDocument();
  });

  it('ligado: só as pagáveis ganham os botões; o Pix gerado mostra o copia e cola e o prazo', async () => {
    const api = apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada, futura]), 'GET /api/pagamentos-online/me': json(200, ligado()),
      'POST /api/pagamentos-online/me': json(201, pix) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText('Pague pelo app, com Pix, boleto ou cartão, ou na recepção.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Pagar com Pix' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Pagar com Pix' }));

    expect(await screen.findByLabelText('Pix copia e cola')).toHaveValue('PIX-COPIA-E-COLA');
    expect(api.de('POST /api/pagamentos-online/me')[0].corpo).toEqual({ cobrancaId: 'c1', forma: 'PIX' });
    // O prazo é o início do dia seguinte: aparece o último minuto do dia em que vale.
    expect(screen.getByText(/Vale até 04\/10\/2026, 23:59/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Pagar com Pix' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Gerar boleto' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Simular/ })).not.toBeInTheDocument();
    // O QR code é o do copia e cola (ADR 0035).
    expect(screen.getByRole('img', { name: 'QR code do Pix' }).querySelector('path')?.getAttribute('d'))
      .toBe(desenhoDoQr('PIX-COPIA-E-COLA').caminho);
  });

  it('só a forma ligada aparece; o já gerado aparece sem pedir de novo', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]),
      'GET /api/pagamentos-online/me': json(200, ligado({ pix: false, pagaveis: [{ cobrancaId: 'c1', total: 102.1, pix: null,
        boleto: { ...pix, id: 'b1', forma: 'BOLETO', pixCopiaECola: null, boletoLinhaDigitavel: '0'.repeat(47), boletoUrl: 'https://empresa.test/b1' } }] })) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByLabelText('Linha digitável do boleto')).toHaveValue('0'.repeat(47));
    expect(screen.getByRole('link', { name: 'Abrir o boleto' })).toHaveAttribute('href', 'https://empresa.test/b1');
    expect(screen.queryByRole('button', { name: 'Pagar com Pix' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gerar boleto' })).not.toBeInTheDocument();
  });

  it('simulado: avisa que é teste, simula e recarrega', async () => {
    let pago = false;
    const api = apiFalsa({
      'GET /api/cobrancas/me': () => json(200, [pago ? { ...atrasada, situacao: 'PAGA', pagaEm: '2026-10-04T15:00:00Z',
        meioDePagamento: 'PIX_ONLINE', pagoTotal: 102.1 } : atrasada]),
      'GET /api/pagamentos-online/me': () => json(200, ligado({ simulado: true,
        pagaveis: pago ? [] : [{ cobrancaId: 'c1', total: 102.1, pix, boleto: null }] })),
      'POST /api/pagamentos-online/me/p1/simular-pagamento': () => { pago = true; return new Response(null, { status: 204 }); },
    });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText(/Modo de teste/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Simular pagamento do Pix (teste)' }));

    expect(await screen.findByText(/Paga em .* \(Pix pelo app\)/)).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/me/p1/simular-pagamento')).toHaveLength(1);
  });

  it('ligado, mas com o Pix e o boleto desligados pelo ADMIN: só na recepção', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]),
      'GET /api/pagamentos-online/me': json(200, ligado({ pix: false, boleto: false })) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    expect(await screen.findByText('O pagamento é feito na recepção.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Gerar boleto' })).not.toBeInTheDocument();
  });

  it('gerar boleto pede o boleto', async () => {
    const api = apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, ligado()),
      'POST /api/pagamentos-online/me': json(201, { ...pix, id: 'b1', forma: 'BOLETO', pixCopiaECola: null, boletoLinhaDigitavel: '1'.repeat(47) }) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    await userEvent.click(await screen.findByRole('button', { name: 'Gerar boleto' }));
    expect(await screen.findByLabelText('Linha digitável do boleto')).toHaveValue('1'.repeat(47));
    expect(screen.queryByRole('img', { name: /QR code/ })).not.toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/me')[0].corpo).toEqual({ cobrancaId: 'c1', forma: 'BOLETO' });
  });

  it('erro ao gerar aparece no cartão', async () => {
    apiFalsa({ 'GET /api/cobrancas/me': json(200, [atrasada]), 'GET /api/pagamentos-online/me': json(200, ligado()),
      'POST /api/pagamentos-online/me': json(503, { detail: 'A empresa de pagamentos não respondeu. Tente de novo em alguns minutos.' }) });
    renderizar(<MeusPagamentos />, { perfil: 'ALUNO' });

    await userEvent.click(await screen.findByRole('button', { name: 'Pagar com Pix' }));
    expect(await screen.findByText(/não respondeu/)).toBeInTheDocument();
  });
});
