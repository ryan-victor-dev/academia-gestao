import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { MeusDados } from '../paginas/MeusDados';
import { apiFalsa, json, renderizar } from '../testes/ajudantes';
import { Identidade } from './Identidade';

const vazia = { identidadeDeGenero: null, identidadeDeGeneroOutra: null, orientacaoSexual: null, orientacaoSexualOutra: null };

describe('identidade de gênero e orientação sexual (ADR 0047)', () => {
  it('o aluno usa a rota do cadastro; a equipe, a do login', async () => {
    const api = apiFalsa({ 'GET /api/alunos/me/identidade': json(200, vazia), 'GET /api/me/identidade': json(200, vazia) });
    const { unmount } = renderizar(<MeusDados />, { perfil: 'ALUNO' });
    await screen.findByLabelText('Identidade de gênero');
    expect(api.de('GET /api/alunos/me/identidade')).toHaveLength(1);
    expect(api.de('GET /api/me/identidade')).toHaveLength(0);
    unmount();

    renderizar(<MeusDados />, { perfil: 'INSTRUTOR' });
    await screen.findByLabelText('Identidade de gênero');
    expect(api.de('GET /api/me/identidade')).toHaveLength(1);
  });

  it('mostra o que foi respondido, com todas as opções e "prefiro não informar"', async () => {
    apiFalsa({ 'GET /x': json(200, { ...vazia, identidadeDeGenero: 'OUTRA', identidadeDeGeneroOutra: 'bigênero', orientacaoSexual: 'PANSEXUAL' }) });
    renderizar(<Identidade caminho="/x" titulo="Identidade" />);

    expect(await screen.findByLabelText('Identidade de gênero')).toHaveValue('OUTRA');
    expect(screen.getByLabelText('Descreva a identidade de gênero')).toHaveValue('bigênero');
    expect(screen.getByLabelText('Orientação sexual')).toHaveValue('PANSEXUAL');
    expect(screen.queryByLabelText('Descreva a orientação sexual')).not.toBeInTheDocument();
    for (const opcao of ['Mulher trans', 'Homem trans', 'Travesti', 'Pessoa não binária', 'Gênero fluido', 'Agênero', 'Prefiro não informar']) {
      expect(screen.getByLabelText('Identidade de gênero')).toHaveTextContent(opcao);
    }
    for (const opcao of ['Lésbica', 'Gay', 'Bissexual', 'Assexual', 'Demissexual', 'Queer', 'Prefiro não informar']) {
      expect(screen.getByLabelText('Orientação sexual')).toHaveTextContent(opcao);
    }
  });

  it('salva só a descrição da opção "outra", e "Não respondido" vai nulo', async () => {
    const api = apiFalsa({ 'GET /x': json(200, vazia), 'PUT /x': (p) => json(200, p.corpo) });
    renderizar(<Identidade caminho="/x" titulo="Identidade" />);

    await userEvent.selectOptions(await screen.findByLabelText('Orientação sexual'), 'OUTRA');
    await userEvent.type(screen.getByLabelText('Descreva a orientação sexual'), 'demissexual e pan');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Salvo.')).toBeInTheDocument();
    expect(api.de('PUT /x')[0].corpo).toEqual({ identidadeDeGenero: null, identidadeDeGeneroOutra: null,
      orientacaoSexual: 'OUTRA', orientacaoSexualOutra: 'demissexual e pan' });

    // Mudar qualquer uma das duas tira o "Salvo." (ainda não foi salvo).
    await userEvent.selectOptions(screen.getByLabelText('Orientação sexual'), 'GAY');
    expect(screen.queryByText('Salvo.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Salvo.');
    await userEvent.selectOptions(screen.getByLabelText('Identidade de gênero'), 'HOMEM_CIS');
    expect(screen.queryByText('Salvo.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Salvo.');
    expect(api.de('PUT /x')[2].corpo).toEqual({ identidadeDeGenero: 'HOMEM_CIS', identidadeDeGeneroOutra: null,
      orientacaoSexual: 'GAY', orientacaoSexualOutra: null });

    // Apagar: "Não respondido" vai nulo, e não texto vazio.
    await userEvent.selectOptions(screen.getByLabelText('Orientação sexual'), '');
    await userEvent.selectOptions(screen.getByLabelText('Identidade de gênero'), '');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Salvo.');
    expect(api.de('PUT /x')[3].corpo).toEqual({ identidadeDeGenero: null, identidadeDeGeneroOutra: null,
      orientacaoSexual: null, orientacaoSexualOutra: null });
  });

  it('a recusa da API aparece', async () => {
    apiFalsa({ 'GET /x': json(200, vazia), 'PUT /x': json(400, { detail: 'orientacaoSexualOutra: descreva a opção "outra"' }) });
    renderizar(<Identidade caminho="/x" titulo="Identidade" />);
    await userEvent.selectOptions(await screen.findByLabelText('Orientação sexual'), 'BISSEXUAL');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('descreva a opção');
  });

  it('só de leitura: mostra, mas não deixa mudar', async () => {
    apiFalsa({ 'GET /x': json(200, { ...vazia, orientacaoSexual: 'GAY' }) });
    renderizar(<Identidade caminho="/x" titulo="Identidade" somenteLeitura />);
    expect(await screen.findByLabelText('Orientação sexual')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Salvar' })).not.toBeInTheDocument();
    expect(screen.getByText(/fale com a administração/)).toBeInTheDocument();
  });
});
