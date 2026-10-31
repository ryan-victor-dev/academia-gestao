import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CadastroDoAluno } from './CadastroDoAluno';

const ID = '00000000-0000-0000-0000-0000000000a1';
const ana = { id: ID, nome: 'Ana Souza', cpf: '12345678909', email: 'ana@x.com', telefone: null,
  dataNascimento: '1990-05-02', observacoes: 'Lesão no joelho', ativo: true, criadoEm: '2026-10-01T12:00:00Z' };
const base = {
  'GET /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, ana, { ETag: '"4"' }),
  'GET /api/matriculas/vigente': json(200, { alunoId: 'a1', podeTreinar: true }),
  'GET /api/matriculas': json(200, []),
  'GET /api/planos': json(200, []),
  'GET /api/cobrancas': json(200, []),
};
const abrir = (perfil: 'RECEPCAO' | 'INSTRUTOR' | 'ADMIN' = 'RECEPCAO') =>
  renderizar(<CadastroDoAluno />, { perfil, rota: '/alunos/:id', endereco: '/alunos/00000000-0000-0000-0000-0000000000a1' });

describe('cadastro do aluno', () => {
  it('recepção vê tudo, com o CPF formatado e se pode treinar', async () => {
    apiFalsa(base);
    abrir();

    expect(await screen.findByRole('heading', { name: 'Ana Souza' })).toBeInTheDocument();
    expect(screen.getByText('123.456.789-09')).toBeInTheDocument();
    expect(screen.getByText('Pode treinar')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Matrículas' })).toBeInTheDocument();
  });

  it('não pode treinar: avisa', async () => {
    apiFalsa({ ...base, 'GET /api/matriculas/vigente': json(200, { alunoId: 'a1', podeTreinar: false }) });
    abrir();

    expect(await screen.findByText('Não pode treinar')).toBeInTheDocument();
  });

  it('editar manda a versão lida e campo vazio como null', async () => {
    const api = apiFalsa({ ...base, 'PUT /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, { ...ana, email: null }, { ETag: '"5"' }) });
    abrir();
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));

    await userEvent.clear(screen.getByLabelText('E-mail'));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const put = api.de('PUT /api/alunos/00000000-0000-0000-0000-0000000000a1')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"4"');
    expect(put.corpo).toMatchObject({ nome: 'Ana Souza', email: null, telefone: null, observacoes: 'Lesão no joelho' });
    expect(await screen.findByRole('button', { name: 'Editar' })).toBeInTheDocument();
  });

  it('nascimento (ADR 0048): mostra a idade; quem tem não apaga; quem é de antes edita sem', async () => {
    apiFalsa(base);
    const { unmount } = abrir();
    expect(await screen.findByText(/^02\/05\/1990 \(\d+ anos\)$/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Editar' }));
    expect(screen.getByLabelText('Nascimento')).toBeRequired();
    fireEvent.change(screen.getByLabelText('Nascimento'), { target: { value: '' } });
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled();
    unmount();

    const api = apiFalsa({ ...base, 'GET /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, { ...ana, dataNascimento: null }, { ETag: '"4"' }),
      'PUT /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, { ...ana, dataNascimento: null }, { ETag: '"5"' }) });
    abrir();
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));
    expect(screen.getByLabelText('Nascimento')).not.toBeRequired();
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(api.de('PUT /api/alunos/00000000-0000-0000-0000-0000000000a1')[0].corpo).toMatchObject({ dataNascimento: null });
  });

  it('outra pessoa alterou antes (412): explica o que fazer', async () => {
    apiFalsa({ ...base, 'PUT /api/alunos/00000000-0000-0000-0000-0000000000a1': json(412, { detail: 'versão desatualizada' }) });
    abrir();
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Outra pessoa alterou este cadastro');
  });

  it('erro de campo aparece com o nome do campo', async () => {
    apiFalsa({ ...base, 'PUT /api/alunos/00000000-0000-0000-0000-0000000000a1': json(400, { detail: 'Um ou mais campos estão inválidos.',
      erros: [{ campo: 'cpf', mensagem: 'CPF inválido' }] }) });
    abrir();
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('CPF: CPF inválido')).toBeInTheDocument();
  });

  it('inativar recusado (matrícula vigente): mostra o motivo da API', async () => {
    apiFalsa({ ...base, 'POST /api/alunos/00000000-0000-0000-0000-0000000000a1/inativar': json(409, { detail: 'o aluno tem matrícula vigente' }) });
    abrir();

    await userEvent.click(await screen.findByRole('button', { name: 'Inativar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('o aluno tem matrícula vigente');
  });

  it('inativar troca para reativar', async () => {
    apiFalsa({ ...base, 'POST /api/alunos/00000000-0000-0000-0000-0000000000a1/inativar': json(200, { ...ana, ativo: false }, { ETag: '"5"' }) });
    abrir();

    await userEvent.click(await screen.findByRole('button', { name: 'Inativar' }));

    expect(await screen.findByRole('button', { name: 'Reativar' })).toBeInTheDocument();
    expect(screen.getByText('Inativo')).toBeInTheDocument();
  });

  it('professor: sem CPF, contato, edição, matrículas nem acesso', async () => {
    const { cpf: _c, email: _e, telefone: _t, criadoEm: _d, ...paraInstrutor } = ana;
    apiFalsa({ ...base, 'GET /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, paraInstrutor) });
    abrir('INSTRUTOR');

    expect(await screen.findByText('Lesão no joelho')).toBeInTheDocument();
    expect(screen.queryByText('CPF')).not.toBeInTheDocument();
    expect(screen.queryByText('E-mail')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Matrículas' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Acesso ao app' })).not.toBeInTheDocument();
  });

  it('id que não é de aluno (ex.: /alunos/novo do professor): não encontrada, sem chamar a API', async () => {
    const api = apiFalsa({});
    renderizar(<CadastroDoAluno />, { perfil: 'INSTRUTOR', rota: '/alunos/:id', endereco: '/alunos/novo' });

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument();
    expect(api.pedidos).toHaveLength(0);
  });

  it('nova matrícula recarrega as cobranças (a matrícula as gera)', async () => {
    const api = apiFalsa({ ...base,
      'GET /api/planos': json(200, [{ id: 'p1', nome: 'Mensal', valorMensal: 100, duracaoMeses: 1, ativo: true }]),
      'POST /api/matriculas': json(201, {}) });
    abrir();
    await userEvent.click(await screen.findByRole('button', { name: 'Nova matrícula' }));
    await userEvent.selectOptions(screen.getByLabelText('Plano'), 'p1');

    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    await vi.waitFor(() => expect(api.de('GET /api/cobrancas')).toHaveLength(2));
  });

  it('o professor vê o treino do aluno na página dele', async () => {
    const { cpf: _c, email: _e, telefone: _t, criadoEm: _d, ...paraInstrutor } = ana;
    apiFalsa({ ...base, 'GET /api/alunos/00000000-0000-0000-0000-0000000000a1': json(200, paraInstrutor),
      'GET /api/fichas/atual': json(404, {}) });
    abrir('INSTRUTOR');

    expect(await screen.findByRole('heading', { name: 'Treino' })).toBeInTheDocument();
    expect(await screen.findByText('Nenhuma ficha montada.')).toBeInTheDocument();
  });

  it('excluir: só o ADMIN; depois de excluir, volta à lista', async () => {
    apiFalsa(base);
    const { unmount } = abrir('RECEPCAO');
    await screen.findByRole('heading', { name: 'Ana Souza' });
    expect(screen.queryByRole('button', { name: 'Excluir' })).not.toBeInTheDocument();
    unmount();

    const api = apiFalsa({ ...base, 'DELETE /api/alunos/00000000-0000-0000-0000-0000000000a1': new Response(null, { status: 204 }) });
    abrir('ADMIN');
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(api.de('DELETE /api/alunos/00000000-0000-0000-0000-0000000000a1')).toHaveLength(1);
    expect(await screen.findByText(/Fora da tela/)).toBeInTheDocument();
  });

  it('excluir aluno com histórico: fica no cadastro e mostra o motivo', async () => {
    apiFalsa({ ...base, 'DELETE /api/alunos/00000000-0000-0000-0000-0000000000a1': json(409, { title: 'Aluno com histórico',
      detail: 'O aluno já tem matrícula, ficha, pagamento ou pedido de primeiro acesso e não pode ser excluído; inative-o' }) });
    abrir('ADMIN');
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(await screen.findByText(/não pode ser excluído; inative-o/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Ana Souza' })).toBeInTheDocument();
  });

  it('identidade de gênero e orientação (ADR 0047): só o ADMIN vê; a recepção nem pede', async () => {
    const api = apiFalsa({ ...base, 'GET /api/alunos/00000000-0000-0000-0000-0000000000a1/identidade':
      json(200, { identidadeDeGenero: 'MULHER_TRANS', identidadeDeGeneroOutra: null, orientacaoSexual: null, orientacaoSexualOutra: null }) });
    const { unmount } = abrir('RECEPCAO');
    await screen.findByRole('heading', { name: 'Ana Souza' });
    await screen.findByRole('heading', { name: 'Matrículas' });
    expect(screen.queryByText(/Identidade de gênero/)).not.toBeInTheDocument();
    unmount();

    abrir('ADMIN');
    expect(await screen.findByLabelText('Identidade de gênero')).toHaveValue('MULHER_TRANS');
    expect(screen.getByRole('heading', { name: /só a administração vê/ })).toBeInTheDocument();
    expect(api.de('GET /api/alunos/00000000-0000-0000-0000-0000000000a1/identidade')).toHaveLength(1);
  });
});
