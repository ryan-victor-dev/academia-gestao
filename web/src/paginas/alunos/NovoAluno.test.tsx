import { fireEvent, screen } from '@testing-library/react';
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
    fireEvent.change(screen.getByLabelText('Nascimento'), { target: { value: '1990-05-02' } });
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar' }));

    expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
    expect(api.de('POST /api/alunos')[0].corpo).toEqual({ nome: 'Carla Dias', cpf: '529.982.247-25', email: null,
      telefone: null, dataNascimento: '1990-05-02', observacoes: null });
  });

  it('sem a data de nascimento não envia (ADR 0048)', async () => {
    apiFalsa({});
    renderizar(<NovoAluno />, { rota: '/alunos/novo' });

    await userEvent.type(screen.getByLabelText('Nome'), 'Carla');
    await userEvent.type(screen.getByLabelText('CPF'), '529.982.247-25');

    expect(screen.getByLabelText('Nascimento')).toBeRequired();
    expect(screen.getByRole('button', { name: 'Cadastrar' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Nascimento'), { target: { value: '1990-05-02' } });
    expect(screen.getByRole('button', { name: 'Cadastrar' })).toBeEnabled();
  });

  it('sem nome ou CPF não envia', async () => {
    apiFalsa({});
    renderizar(<NovoAluno />, { rota: '/alunos/novo' });

    await userEvent.type(screen.getByLabelText('Nome'), 'Carla');

    expect(screen.getByRole('button', { name: 'Cadastrar' })).toBeDisabled();
  });
});
