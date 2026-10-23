import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { AcessoDoAluno } from './AcessoDoAluno';

describe('acesso do aluno ao app', () => {
  it('criar acesso mostra login e senha temporária uma vez; esconder apaga da tela', async () => {
    apiFalsa({ 'POST /api/alunos/a1/login': json(201, { email: 'ana@x.com', senhaTemporaria: 'Abc-123-xyz' }) });
    renderizar(<AcessoDoAluno alunoId="a1" temEmail ativo />);

    await userEvent.click(screen.getByRole('button', { name: 'Criar acesso' }));

    expect(await screen.findByText('Abc-123-xyz')).toBeInTheDocument();
    expect(screen.getByText(/a senha não aparece de novo/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Já passei, esconder' }));
    expect(screen.queryByText('Abc-123-xyz')).not.toBeInTheDocument();
  });

  it('senha nova usa a rota do "esqueci minha senha"', async () => {
    const api = apiFalsa({ 'POST /api/alunos/a1/login/nova-senha': json(200, { email: 'ana@x.com', senhaTemporaria: 'Nova-456' }) });
    renderizar(<AcessoDoAluno alunoId="a1" temEmail ativo />);

    await userEvent.click(screen.getByRole('button', { name: 'Gerar senha nova' }));

    expect(await screen.findByText('Nova-456')).toBeInTheDocument();
    expect(api.de('POST /api/alunos/a1/login/nova-senha')).toHaveLength(1);
  });

  it('já tem acesso (409): mostra o motivo', async () => {
    apiFalsa({ 'POST /api/alunos/a1/login': json(409, { detail: 'o aluno já tem login' }) });
    renderizar(<AcessoDoAluno alunoId="a1" temEmail ativo />);

    await userEvent.click(screen.getByRole('button', { name: 'Criar acesso' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('o aluno já tem login');
  });

  it('sem e-mail: não cria; inativo: nem cria nem gera senha', () => {
    apiFalsa({});
    const { unmount } = renderizar(<AcessoDoAluno alunoId="a1" temEmail={false} ativo />);
    expect(screen.getByRole('button', { name: 'Criar acesso' })).toBeDisabled();
    expect(screen.getByText(/cadastre antes o e-mail/)).toBeInTheDocument();
    unmount();

    renderizar(<AcessoDoAluno alunoId="a1" temEmail ativo={false} />);
    expect(screen.getByRole('button', { name: 'Criar acesso' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Gerar senha nova' })).toBeDisabled();
  });
});
