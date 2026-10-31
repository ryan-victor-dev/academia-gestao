import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { PessoasDaFolha } from './PessoasDaFolha';
import type { PessoaDaFolha } from './tipos';

const pessoa = (mudancas: Partial<PessoaDaFolha>): PessoaDaFolha => ({
  id: 'p1', usuarioId: 'u1', nome: 'Rita Recepção', cpf: '52998224725', perfil: 'RECEPCAO', vinculo: 'CLT',
  valorCombinado: 2500, ativo: true, versao: 3, ...mudancas,
});
const rita = pessoa({});
const ivo = pessoa({ id: null, usuarioId: 'u2', nome: 'Ivo Professor', perfil: 'INSTRUTOR', vinculo: null, valorCombinado: null, cpf: null, versao: 0 });
const rosa = pessoa({ id: 'p3', usuarioId: null, nome: 'Rosa da Limpeza', cpf: '11144477735', perfil: null, vinculo: 'MEI', valorCombinado: null, versao: 1 });

const cartao = (nome: string) => screen.getByText(nome).closest('li') as HTMLElement;

describe('pessoas da folha', () => {
  it('mostra o perfil ou "Sem login", o vínculo, o valor e quem ainda não tem vínculo', async () => {
    apiFalsa({ 'GET /api/folha/pessoas': json(200, [ivo, rita, rosa]) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });

    await screen.findByText('Rita Recepção');
    expect(within(cartao('Rita Recepção')).getByText(/CLT · R\$\s2\.500,00 por mês/)).toBeInTheDocument();
    expect(within(cartao('Rita Recepção')).getByText('Recepção')).toBeInTheDocument();
    expect(within(cartao('Rita Recepção')).getByText('CPF 529.982.247-25')).toBeInTheDocument();
    expect(within(cartao('Rosa da Limpeza')).getByText('Sem login')).toBeInTheDocument();
    expect(within(cartao('Rosa da Limpeza')).getByText('MEI · sem valor fixo')).toBeInTheDocument();
    expect(within(cartao('Ivo Professor')).getByText('Sem vínculo')).toBeInTheDocument();
    expect(within(cartao('Ivo Professor')).getByRole('button', { name: 'Definir vínculo' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('1 pessoa da equipe ainda não tem o vínculo definido.');
  });

  it('definir o vínculo de quem tem login: manda o usuarioId, sem nome nem CPF', async () => {
    const api = apiFalsa({ 'GET /api/folha/pessoas': json(200, [ivo]), 'POST /api/folha/pessoas': json(201, {}) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Definir vínculo' }));

    expect(screen.queryByLabelText('Nome')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText('Vínculo'), 'MEI');
    await userEvent.type(screen.getByLabelText('Valor combinado por mês (R$)'), '1800.5');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('POST /api/folha/pessoas')[0].corpo).toEqual({ vinculo: 'MEI', valorCombinado: 1800.5, usuarioId: 'u2' });
    expect(api.de('GET /api/folha/pessoas')).toHaveLength(2);
  });

  it('nova pessoa sem login: nome, CPF e vínculo; sem valor, manda null', async () => {
    const api = apiFalsa({ 'GET /api/folha/pessoas': json(200, []), 'POST /api/folha/pessoas': json(201, {}) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });
    expect(await screen.findByText('Ninguém na folha.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Nova pessoa sem login' }));

    await userEvent.type(screen.getByLabelText('Nome'), ' Marcos ');
    await userEvent.selectOptions(screen.getByLabelText('Vínculo'), 'AUTONOMO');
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('CPF'), '111.444.777-35');
    expect(screen.queryByLabelText('Ativa')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('POST /api/folha/pessoas')[0].corpo)
      .toEqual({ nome: 'Marcos', cpf: '111.444.777-35', vinculo: 'AUTONOMO', valorCombinado: null });
  });

  it('editar quem tem login manda só o combinado, com a versão', async () => {
    const api = apiFalsa({ 'GET /api/folha/pessoas': json(200, [rita]), 'PUT /api/folha/pessoas/p1': json(200, rita) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));

    expect(screen.getByLabelText('Valor combinado por mês (R$)')).toHaveValue(2500);
    await userEvent.clear(screen.getByLabelText('Valor combinado por mês (R$)'));
    await userEvent.type(screen.getByLabelText('Valor combinado por mês (R$)'), '2600');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const put = api.de('PUT /api/folha/pessoas/p1')[0];
    expect(put.corpo).toEqual({ vinculo: 'CLT', valorCombinado: 2600 });
    expect(put.cabecalhos.get('If-Match')).toBe('"3"');
  });

  it('editar quem não tem login: nome, CPF e inativar; outra pessoa alterou antes', async () => {
    const api = apiFalsa({ 'GET /api/folha/pessoas': json(200, [rosa]),
      'PUT /api/folha/pessoas/p3': json(412, { detail: 'alterado' }) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));

    expect(screen.getByLabelText('CPF')).toHaveValue('111.444.777-35');
    await userEvent.click(screen.getByLabelText('Ativa'));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('PUT /api/folha/pessoas/p3')[0].corpo)
      .toEqual({ nome: 'Rosa da Limpeza', cpf: '111.444.777-35', vinculo: 'MEI', valorCombinado: null, ativo: false });
    expect(await screen.findByRole('alert')).toHaveTextContent('Outra pessoa alterou este cadastro. Cancele e abra de novo.');
  });

  it('inativos: pede à API', async () => {
    const api = apiFalsa({ 'GET /api/folha/pessoas': (p) => json(200, p.busca.get('incluirInativos') ? [rita, { ...rosa, ativo: false }] : [rita]) });
    renderizar(<PessoasDaFolha />, { perfil: 'ADMIN' });
    await screen.findByText('Rita Recepção');
    await userEvent.click(screen.getByLabelText('Mostrar inativos'));

    expect(await screen.findByText('Inativa')).toBeInTheDocument();
    expect(api.de('GET /api/folha/pessoas').at(-1)?.busca.get('incluirInativos')).toBe('true');
  });
});
