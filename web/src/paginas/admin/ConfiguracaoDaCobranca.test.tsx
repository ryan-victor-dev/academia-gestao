import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { ConfiguracaoDaCobranca } from './ConfiguracaoDaCobranca';

const atual = { tipoAcrescimo: 'PERCENTUAL', acrescimo: 2, jurosAoMes: 1, diasParaBloquear: 10, tetoDoAcrescimoPercentual: 2 };

describe('configuração da cobrança', () => {
  it('mostra a atual e salva com a versão lida, números como número', async () => {
    const api = apiFalsa({ 'GET /api/cobrancas/configuracao': json(200, atual, { ETag: '"3"' }),
      'PUT /api/cobrancas/configuracao': json(200, { ...atual, tipoAcrescimo: 'VALOR', acrescimo: 5 }, { ETag: '"4"' }) });
    renderizar(<ConfiguracaoDaCobranca />, { perfil: 'ADMIN' });

    expect(await screen.findByLabelText('Acréscimo (%)')).toHaveValue(2);
    await userEvent.selectOptions(screen.getByLabelText('Tipo do acréscimo'), 'VALOR');
    await userEvent.clear(screen.getByLabelText('Acréscimo (R$)'));
    await userEvent.type(screen.getByLabelText('Acréscimo (R$)'), '5');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const put = api.de('PUT /api/cobrancas/configuracao')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"3"');
    expect(put.corpo).toEqual({ tipoAcrescimo: 'VALOR', acrescimo: 5, jurosAoMes: 1, diasParaBloquear: 10 });
    expect(await screen.findByText('Configuração salva.')).toBeInTheDocument();
  });

  it('exemplo corta o acréscimo no teto de 2%', async () => {
    apiFalsa({ 'GET /api/cobrancas/configuracao': json(200, { ...atual, tipoAcrescimo: 'VALOR', acrescimo: 7, jurosAoMes: 2 }) });
    renderizar(<ConfiguracaoDaCobranca />, { perfil: 'ADMIN' });

    expect(await screen.findByText(/acréscimo de R\$\s2,00 e juros de R\$\s2,00/)).toBeInTheDocument();
  });

  it('valor fora da regra: a API explica', async () => {
    apiFalsa({ 'GET /api/cobrancas/configuracao': json(200, atual),
      'PUT /api/cobrancas/configuracao': json(400, { detail: 'juros ao mês vão de 0 a 10' }) });
    renderizar(<ConfiguracaoDaCobranca />, { perfil: 'ADMIN' });
    await userEvent.clear(await screen.findByLabelText('Juros ao mês (%)'));
    await userEvent.type(screen.getByLabelText('Juros ao mês (%)'), '50');

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('juros ao mês vão de 0 a 10');
  });
});
