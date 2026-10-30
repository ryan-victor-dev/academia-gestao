import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { hoje } from '../formato';
import { MeusDados } from '../paginas/MeusDados';
import { apiFalsa, json, renderizar } from '../testes/ajudantes';
import { MeuNascimento } from './MeuNascimento';

const vazia = { identidadeDeGenero: null, identidadeDeGeneroOutra: null, orientacaoSexual: null, orientacaoSexualOutra: null };

describe('a própria data de nascimento (ADR 0048)', () => {
  it('só a equipe informa a sua em "Meus dados"; a do aluno fica no cadastro', async () => {
    apiFalsa({ 'GET /api/alunos/me/identidade': json(200, vazia), 'GET /api/me/identidade': json(200, vazia) });
    const { unmount } = renderizar(<MeusDados />, { perfil: 'ALUNO' });
    await screen.findByLabelText('Identidade de gênero');
    expect(screen.queryByRole('form', { name: 'Minha data de nascimento' })).not.toBeInTheDocument();
    unmount();

    renderizar(<MeusDados />, { perfil: 'INSTRUTOR' });
    expect(screen.getByRole('form', { name: 'Minha data de nascimento' })).toBeInTheDocument();
  });

  it('sem data, avisa; ao salvar, manda a data e mostra a idade', async () => {
    const nascimento = `${Number(hoje().slice(0, 4)) - 40}-01-01`;
    const api = apiFalsa({ 'PUT /api/me/nascimento': json(200, { dataNascimento: nascimento }) });
    renderizar(<MeuNascimento inicial={null} />);

    expect(screen.getByText('Ainda não informada')).toBeInTheDocument();
    expect(screen.getByLabelText('Data de nascimento')).toHaveAttribute('max', hoje());
    fireEvent.change(screen.getByLabelText('Data de nascimento'), { target: { value: nascimento } });
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Salvo.')).toBeInTheDocument();
    expect(api.de('PUT /api/me/nascimento')[0].corpo).toEqual({ dataNascimento: nascimento });
    expect(screen.getByText(/40 anos/)).toBeInTheDocument();
    expect(screen.queryByText('Ainda não informada')).not.toBeInTheDocument();
  });

  it('com data, mostra a data e a idade; recusa da API aparece', async () => {
    apiFalsa({ 'PUT /api/me/nascimento': json(400, { detail: 'data de nascimento não pode estar no futuro' }) });
    renderizar(<MeuNascimento inicial="1990-05-10" />);

    expect(screen.getByText(/10\/05\/1990/)).toBeInTheDocument();
    expect(screen.getByLabelText('Data de nascimento')).toHaveValue('1990-05-10');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('não pode estar no futuro');
  });
});
