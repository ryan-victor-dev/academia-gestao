import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { PagamentosOnline } from './PagamentosOnline';

const atual = { provedor: 'simulado', simulado: true, pixLigado: true, boletoLigado: true, adiantar: 'PROXIMA', diasDoBoleto: 3 };
const aDevolver = { id: 'p9', forma: 'BOLETO', alunoNome: 'Maria', total: 103, recebido: 103, recebidoEm: '2026-10-04T15:00:00Z',
  motivoDaDevolucao: 'A cobrança já não estava em aberto (paga por outro meio ou cancelada)' };

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
    await userEvent.selectOptions(screen.getByLabelText('Até onde adiantar'), 'TODAS');
    await userEvent.clear(screen.getByLabelText(/Dias de validade/));
    await userEvent.type(screen.getByLabelText(/Dias de validade/), '5');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Configuração salva.')).toBeInTheDocument();
    const put = api.de('PUT /api/pagamentos-online/configuracao')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"1"');
    expect(put.corpo).toEqual({ pixLigado: true, boletoLigado: false, adiantar: 'TODAS', diasDoBoleto: 5 });
  });

  it('desligado: explica que a empresa é do servidor', async () => {
    apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, { ...atual, provedor: 'desligado', simulado: false }),
      'GET /api/pagamentos-online/a-devolver': json(200, []) });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/Nenhuma empresa de pagamentos configurada/)).toBeInTheDocument();
    expect(screen.queryByText(/Modo de teste/)).not.toBeInTheDocument();
  });

  it('a devolver: com o nome e o motivo; marcar como devolvido recarrega', async () => {
    let devolvido = false;
    const api = apiFalsa({ 'GET /api/pagamentos-online/configuracao': json(200, atual),
      'GET /api/pagamentos-online/a-devolver': () => json(200, devolvido ? [] : [aDevolver]),
      'POST /api/pagamentos-online/p9/devolvido': () => { devolvido = true; return json(200, { ...aDevolver, situacao: 'DEVOLVIDO' }); } });
    renderizar(<PagamentosOnline />, { perfil: 'ADMIN' });

    expect(await screen.findByText('Maria')).toBeInTheDocument();
    expect(screen.getByText(/já não estava em aberto/)).toBeInTheDocument();
    expect(screen.getByText(/Boleto recebido em/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Marcar como devolvido' }));

    expect(await screen.findByText('Nada a devolver.')).toBeInTheDocument();
    expect(api.de('POST /api/pagamentos-online/p9/devolvido')).toHaveLength(1);
  });
});
