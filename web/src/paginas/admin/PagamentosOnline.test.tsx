import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { PagamentosOnline } from './PagamentosOnline';

const atual = { provedor: 'simulado', simulado: true, pixLigado: true, boletoLigado: true, creditoLigado: true, debitoLigado: true,
  adiantar: 'PROXIMA', diasDoBoleto: 3, formasDaEmpresa: ['PIX', 'BOLETO', 'CREDITO', 'DEBITO'] };
const aDevolver = { id: 'p9', forma: 'BOLETO', alunoNome: 'Maria', total: 103, recebido: 103, recebidoEm: '2026-10-04T15:00:00Z',
  motivoDaDevolucao: 'A cobrança já não estava em aberto (paga por outro meio ou cancelada)', valorADevolver: 103,
  pagouACobranca: false, devolvePeloSistema: false, situacao: 'A_DEVOLVER' };
const sobra = { ...aDevolver, id: 'p8', forma: 'PIX', recebido: 110, valorADevolver: 7, pagouACobranca: true, devolvePeloSistema: true,
  motivoDaDevolucao: 'Pago a mais: recebido 110.00, e o combinado era 103.00; a cobrança ficou paga e a sobra volta' };

describe('pagamento online (ADMIN)', () => {
  it('mostra a empresa e salva com a versão lida', async () => {
    const api = apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual, { ETag: '"1"' }),
      'GET /api/pagamentos-online/a-devolver': json(200, []),
      'PUT /api/pagamentos-online/configuracao': json(200, { ...atual, boletoLigado: false, adiantar: 'TODAS', diasDoBoleto: 5 }, { ETag: '"2"' }) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Empresa de pagamentos: simulado/)).toBeInTheDocument();
    expect(screen.getByText(/Modo de teste/)).toBeInTheDocument();
    expect(screen.getByText('Nada a devolver.')).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('Boleto pelo app'));
    await userEvent.click(screen.getByLabelText('Cartão de débito pelo app'));
    await userEvent.selectOptions(screen.getByLabelText('Até onde adiantar'), 'TODAS');
    await userEvent.clear(screen.getByLabelText(/Dias de validade/));
    await userEvent.type(screen.getByLabelText(/Dias de validade/), '5');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Configuração salva.')).toBeInTheDocument();
    const put = api.de('PUT /api/pagamentos-online/configuracao')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"1"');
    expect(put.corpo).toEqual({ pixLigado: true, boletoLigado: false, creditoLigado: true, debitoLigado: false, adiantar: 'TODAS',
      diasDoBoleto: 5 });
    expect(screen.queryByText(/não oferece/)).not.toBeInTheDocument();
  });

  it('a forma que a empresa não oferece: avisa ao lado da chave', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, { ...atual, formasDaEmpresa: ['PIX', 'BOLETO', 'CREDITO'] }),
      'GET /api/pagamentos-online/a-devolver': json(200, []) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByLabelText(/Cartão de débito pelo app/)).toBeInTheDocument();
    expect(screen.getAllByText(/a empresa de pagamentos não oferece/)).toHaveLength(1);
    expect(screen.getByText(/a empresa de pagamentos não oferece/).closest('label')).toHaveTextContent('Cartão de débito pelo app');
  });

  it('cartão a devolver: com o nome da forma', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': json(200, [{ ...sobra, forma: 'CREDITO' }]) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Cartão de crédito de R\$\s110,00 recebido em/)).toBeInTheDocument();
  });

  it('desligado: explica que a empresa é do servidor', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, { ...atual, provedor: 'desligado', simulado: false }),
      'GET /api/pagamentos-online/a-devolver': json(200, []) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Nenhuma empresa de pagamentos configurada/)).toBeInTheDocument();
    expect(screen.queryByText(/Modo de teste/)).not.toBeInTheDocument();
  });

  it('boleto (a empresa não devolve): com o nome e o motivo; só "devolvi por fora", que recarrega', async () => {
    let devolvido = false;
    const api = apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': () => json(200, devolvido ? [] : [aDevolver]),
      'POST /api/pagamentos-online/p9/devolvido': () => { devolvido = true; return json(200, { ...aDevolver, situacao: 'DEVOLVIDO' }); } });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText('Maria')).toBeInTheDocument();
    expect(screen.getByText(/já não estava em aberto/)).toBeInTheDocument();
    expect(screen.getByText(/Boleto de R\$\s103,00 recebido em/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Devolver/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Já devolvi por fora' }));

    expect(await screen.findByText('Nada a devolver.')).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/p9/devolvido')).toHaveLength(1);
  });

  it('pago a mais: mostra a sobra; devolver pede confirmação, e só então chama a empresa', async () => {
    let devolvido = false;
    const api = apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': () => json(200, devolvido ? [] : [sobra]),
      'POST /api/pagamentos-online/p8/devolver': () => { devolvido = true; return json(200, { ...sobra, situacao: 'DEVOLVIDO' }); } });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/a cobrança ficou paga com R\$\s103,00/)).toBeInTheDocument();
    expect(screen.getByText(/Pix de R\$\s110,00 recebido em/)).toBeInTheDocument();
    // O valor em destaque é o que volta (a sobra), e não o que chegou.
    expect(screen.getByText(/^R\$\s7,00$/)).toHaveClass('valor');
    expect(screen.queryByRole('button', { name: 'Já devolvi por fora' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Devolver R\$\s7,00/ }));
    expect(screen.getByText(/Devolver R\$\s7,00 para Maria pela empresa de pagamentos\?/)).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/p8/devolver')).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }));
    expect(api.de('POST /api/pagamentos-online/p8/devolver')).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: /Devolver R\$\s7,00/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar devolução' }));

    expect(await screen.findByText('Nada a devolver.')).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/p8/devolver')).toHaveLength(1);
  });

  it('devolvendo (parado no meio): avisa, deixa tentar de novo, e não oferece "por fora"', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': json(200, [{ ...sobra, situacao: 'DEVOLVENDO' }]) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Devolução pela empresa em andamento/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar devolver de novo' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Já devolvi por fora' })).not.toBeInTheDocument();
  });

  it('boleto devolvendo (outra empresa que devolve boleto, por exemplo): também sem "por fora"', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': json(200, [{ ...aDevolver, situacao: 'DEVOLVENDO' }]) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Devolução pela empresa em andamento/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Já devolvi por fora' })).not.toBeInTheDocument();
  });

  it('a empresa recusou: mostra o erro, deixa tentar de novo ou devolver por fora', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': json(200, [sobra]),
      'POST /api/pagamentos-online/p8/devolver': json(503, { detail: 'A empresa de pagamentos não respondeu. Tente de novo em alguns minutos.' }) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    await userEvent.click(await screen.findByRole('button', { name: /Devolver R\$\s7,00/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar devolução' }));

    expect(await screen.findByText(/não respondeu/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar devolver de novo' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Já devolvi por fora' })).toBeInTheDocument();
    expect(screen.getByText(/Antes de devolver por fora, tente de novo pelo sistema/)).toBeInTheDocument();
  });
});
