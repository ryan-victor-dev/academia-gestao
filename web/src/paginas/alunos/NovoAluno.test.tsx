import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { NovoAluno } from './NovoAluno';

describe('novo aluno', () => {
  it('cadastra e vai para o cadastro criado', async () => {
    const api = apiFalsa({ 'POST /api/alunos': json(201, { id: 'novo1' }) });
    renderizar(<NovoAluno />, { rota: '/alunos/novo' });

    await userEvent.type(screen.getByLabelText('Nome'), '  Carla Dias ');
    await userEvent.type(screen.getByLabelText('CPF'), '529.982.247-25');
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar' }));

    expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
    expect(api.de('POST /api/alunos')[0].corpo).toEqual({ nome: 'Carla Dias', cpf: '529.982.247-25', email: null,
      telefone: null, dataNascimento: null, observacoes: null });
  });

  it('sem nome ou CPF não envia', async () => {
    apiFalsa({});
    renderizar(<NovoAluno />, { rota: '/alunos/novo' });

    await userEvent.type(screen.getByLabelText('Nome'), 'Carla');

    expect(screen.getByRole('button', { name: 'Cadastrar' })).toBeDisabled();
  });
});
