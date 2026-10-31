import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { RelatorioDePerfil } from './RelatorioDePerfil';

const zeros = (chaves: string[]) => Object.fromEntries(chaves.map((c) => [c, 0]));
const FAIXAS = ['ATE_17', 'DE_18_A_29', 'DE_30_A_39', 'DE_40_A_49', 'DE_50_A_59', 'A_PARTIR_DE_60', 'SEM_DATA'];
const resumo = (total: number, porFaixa: object, porIdentidadeDeGenero: object, porOrientacaoSexual: object) =>
  ({ total, porFaixa: { ...zeros(FAIXAS), ...porFaixa }, porIdentidadeDeGenero, porOrientacaoSexual });
const pessoa = (mudancas: object) => ({ grupo: 'ALUNO', id: 'a1', nome: 'Ana', perfil: null, ativo: true, dataNascimento: '2009-01-01',
  idade: 17, faixa: 'ATE_17', identidadeDeGenero: null, identidadeDeGeneroOutra: null, orientacaoSexual: null,
  orientacaoSexualOutra: null, ...mudancas });
const relatorio = (comInativos: boolean) => ({
  dia: '2026-10-30', comInativos,
  alunos: resumo(comInativos ? 3 : 2, { ATE_17: 1, SEM_DATA: 1, ...(comInativos && { DE_40_A_49: 1 }) },
    { MULHER_CIS: 1, OUTRA: 1, NAO_RESPONDIDO: 0 }, { LESBICA: 1, NAO_RESPONDIDO: 1 }),
  equipe: resumo(1, { DE_30_A_39: 1 }, { HOMEM_TRANS: 1, NAO_RESPONDIDO: 0 }, { GAY: 1, NAO_RESPONDIDO: 0 }),
  pessoas: [
    pessoa({ identidadeDeGenero: 'MULHER_CIS', orientacaoSexual: 'LESBICA' }),
    pessoa({ id: 'a2', nome: 'Caio', dataNascimento: null, idade: null, faixa: 'SEM_DATA', identidadeDeGenero: 'OUTRA',
      identidadeDeGeneroOutra: 'bigênero' }),
    ...(comInativos ? [pessoa({ id: 'a3', nome: 'Duda', ativo: false, dataNascimento: '1986-01-01', idade: 40, faixa: 'DE_40_A_49' })] : []),
    pessoa({ grupo: 'EQUIPE', id: 'u1', nome: 'Rita', perfil: 'RECEPCAO', dataNascimento: '1996-01-01', idade: 30, faixa: 'DE_30_A_39',
      identidadeDeGenero: 'HOMEM_TRANS', orientacaoSexual: 'GAY' }),
  ],
});
const linha = (nome: string) => within(screen.getByRole('list', { name: 'Pessoas' })).getByText(nome).closest('li')!;

describe('relatório de idades e gênero (ADR 0048)', () => {
  it('mostra as contagens de cada grupo, com a porcentagem e quem não respondeu', async () => {
    apiFalsa({ 'GET /api/relatorios/idades-e-genero': json(200, relatorio(false)) });
    renderizar(<RelatorioDePerfil />, { perfil: 'ADMIN' });

    expect(await screen.findByRole('heading', { name: 'Alunos (2)' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Equipe (1)' })).toBeInTheDocument();
    const faixas = screen.getByRole('table', { name: 'Faixa etária: alunos' });
    expect(within(faixas).getByText('Até 17 anos').closest('tr')).toHaveTextContent('Até 17 anos150%');
    expect(within(faixas).getByText('Sem data de nascimento').closest('tr')).toHaveTextContent('150%');
    expect(within(faixas).getByText('60 ou mais').closest('tr')).toHaveTextContent('60 ou mais00%');
    const generos = screen.getByRole('table', { name: 'Identidade de gênero: alunos' });
    expect(within(generos).getByText('Outra')).toBeInTheDocument();
    expect(within(generos).getByText('Mulher cisgênero')).toBeInTheDocument();
    const orientacoes = screen.getByRole('table', { name: 'Orientação sexual: alunos' });
    expect(within(orientacoes).getByText('Não respondido').closest('tr')).toHaveTextContent('Não respondido150%');
    expect(within(screen.getByRole('table', { name: 'Orientação sexual: equipe' })).getByText('Gay').closest('tr'))
      .toHaveTextContent('Gay1100%');
  });

  it('lista as pessoas com a idade, o grupo e o que responderam; "outra" mostra o que a pessoa escreveu', async () => {
    apiFalsa({ 'GET /api/relatorios/idades-e-genero': json(200, relatorio(false)) });
    renderizar(<RelatorioDePerfil />, { perfil: 'ADMIN' });
    await screen.findByRole('list', { name: 'Pessoas' });

    expect(linha('Ana')).toHaveTextContent('01/01/2009 (17 anos)');
    expect(linha('Ana')).toHaveTextContent('Gênero: Mulher cisgênero');
    expect(linha('Ana')).toHaveTextContent('Orientação: Lésbica');
    expect(linha('Caio')).toHaveTextContent('Sem data de nascimento');
    expect(linha('Caio')).toHaveTextContent('Gênero: Outra: bigênero');
    expect(linha('Caio')).toHaveTextContent('Orientação: Não respondido');
    expect(linha('Rita')).toHaveTextContent('Recepção');
    expect(screen.getByText('3 pessoas')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Grupo'), 'EQUIPE');
    expect(screen.getByText('1 pessoa')).toBeInTheDocument();
    expect(within(screen.getByRole('list', { name: 'Pessoas' })).queryByText('Ana')).not.toBeInTheDocument();
  });

  it('incluir os inativos pede de novo, com inativos=true', async () => {
    const api = apiFalsa({ 'GET /api/relatorios/idades-e-genero': (p) => json(200, relatorio(p.busca.get('inativos') === 'true')) });
    renderizar(<RelatorioDePerfil />, { perfil: 'ADMIN' });
    await screen.findByRole('heading', { name: 'Alunos (2)' });
    expect(api.de('GET /api/relatorios/idades-e-genero')[0].busca.get('inativos')).toBe('false');

    await userEvent.click(screen.getByLabelText('Incluir alunos inativos e equipe desativada'));

    expect(await screen.findByRole('heading', { name: 'Alunos (3)' })).toBeInTheDocument();
    expect(linha('Duda')).toHaveTextContent('Inativo');
  });

  it('recusa da API aparece', async () => {
    apiFalsa({ 'GET /api/relatorios/idades-e-genero': json(403, { detail: 'Acesso negado' }) });
    renderizar(<RelatorioDePerfil />, { perfil: 'ADMIN' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Acesso negado');
  });
});
