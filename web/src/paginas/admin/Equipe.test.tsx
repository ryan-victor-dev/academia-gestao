import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { Equipe } from './Equipe';

const usuario = (id: string, nome: string, perfil: string, mudancas: object = {}) =>
  ({ id, nome, email: `${id}@x.com`, perfil, ativo: true, precisaTrocarSenha: false, somenteLeitura: false, ...mudancas });
// O usuário logado nos testes é o "u1" (ajudantes.usuarioDe).
const lista = [usuario('u1', 'Eu Admin', 'ADMIN'), usuario('u2', 'Rita', 'RECEPCAO'), usuario('u3', 'Outro Admin', 'ADMIN'),
  usuario('u4', 'Aluno Ana', 'ALUNO'), usuario('u5', 'Paulo', 'INSTRUTOR', { ativo: false })];
const linha = (nome: string) => screen.getByText(nome, { exact: false, selector: '.principal' }).closest('li')!;

describe('equipe', () => {
  it('lista a equipe; logins de alunos só se pedir', async () => {
    apiFalsa({ 'GET /api/usuarios': json(200, lista) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });

    expect(await screen.findByText('Rita')).toBeInTheDocument();
    expect(screen.queryByText('Aluno Ana')).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('Mostrar também os logins de alunos'));
    expect(screen.getByText('Aluno Ana')).toBeInTheDocument();
  });

  it('a si mesmo não desativa; ADMIN não ganha senha nova; desativado reativa', async () => {
    apiFalsa({ 'GET /api/usuarios': json(200, lista) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });
    await screen.findByText('Rita');

    expect(within(linha('Eu Admin')).queryByRole('button', { name: 'Desativar' })).not.toBeInTheDocument();
    expect(within(linha('Outro Admin')).queryByRole('button', { name: 'Senha nova' })).not.toBeInTheDocument();
    expect(within(linha('Rita')).getByRole('button', { name: 'Senha nova' })).toBeInTheDocument();
    expect(within(linha('Paulo')).getByRole('button', { name: 'Reativar' })).toBeInTheDocument();
  });

  it('novo login mostra a senha temporária uma vez', async () => {
    const api = apiFalsa({ 'GET /api/usuarios': json(200, lista),
      'POST /api/usuarios': json(201, { id: 'n', nome: 'Nova', email: 'nova@x.com', perfil: 'RECEPCAO', senhaTemporaria: 'Tmp-123' }) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });

    await userEvent.click(await screen.findByRole('button', { name: 'Novo login' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nova');
    await userEvent.type(screen.getByLabelText('E-mail (é o login)'), 'nova@x.com');
    await userEvent.selectOptions(screen.getByLabelText('Perfil'), 'RECEPCAO');
    await userEvent.click(screen.getByRole('button', { name: 'Criar' }));

    expect(api.de('POST /api/usuarios')[0].corpo).toEqual({ nome: 'Nova', email: 'nova@x.com', perfil: 'RECEPCAO' });
    expect(await screen.findByText('Tmp-123')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Já passei, esconder' }));
    expect(screen.queryByText('Tmp-123')).not.toBeInTheDocument();
  });

  it('editar o próprio login: só o nome (o perfil vai como está)', async () => {
    const api = apiFalsa({ 'GET /api/usuarios': json(200, lista), 'PUT /api/usuarios/u1': json(200, lista[0]) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });
    await screen.findByText('Rita');
    await userEvent.click(within(linha('Eu Admin')).getByRole('button', { name: 'Editar' }));

    expect(screen.queryByLabelText('Perfil')).not.toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Nome'));
    await userEvent.type(screen.getByLabelText('Nome'), 'Eu Mesmo');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('PUT /api/usuarios/u1')[0].corpo).toEqual({ nome: 'Eu Mesmo', perfil: 'ADMIN' });
  });

  it('mudar o perfil de outro; desativar; recusa da API aparece', async () => {
    const api = apiFalsa({ 'GET /api/usuarios': json(200, lista), 'PUT /api/usuarios/u2': json(200, lista[1]),
      'POST /api/usuarios/u3/desativar': json(409, { detail: 'a academia precisa de pelo menos um ADMIN ativo' }) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });
    await screen.findByText('Rita');
    await userEvent.click(within(linha('Rita')).getByRole('button', { name: 'Editar' }));
    await userEvent.selectOptions(screen.getByLabelText('Perfil'), 'INSTRUTOR');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(api.de('PUT /api/usuarios/u2')[0].corpo).toEqual({ nome: 'Rita', perfil: 'INSTRUTOR' });

    await userEvent.click(within(linha('Outro Admin')).getByRole('button', { name: 'Desativar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('pelo menos um ADMIN ativo');
  });

  it('senha nova da recepção aparece', async () => {
    apiFalsa({ 'GET /api/usuarios': json(200, lista), 'POST /api/usuarios/u2/nova-senha': json(200, { email: 'u2@x.com', senhaTemporaria: 'Nova-789' }) });
    renderizar(<Equipe />, { perfil: 'ADMIN' });

    await userEvent.click(within(await screen.findByText('Rita').then(() => linha('Rita'))).getByRole('button', { name: 'Senha nova' }));

    expect(await screen.findByText('Nova-789')).toBeInTheDocument();
  });
});
