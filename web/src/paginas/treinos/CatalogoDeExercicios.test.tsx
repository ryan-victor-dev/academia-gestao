import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CatalogoDeExercicios } from './CatalogoDeExercicios';

const supino = { id: 'e1', nome: 'Supino', grupoMuscular: 'PEITO', observacao: null, ativo: true };

describe('catálogo de exercícios', () => {
  it('filtra por grupo e mostra inativos', async () => {
    const api = apiFalsa({ 'GET /api/exercicios': json(200, [supino]) });
    renderizar(<CatalogoDeExercicios />, { perfil: 'INSTRUTOR' });
    await screen.findByText('Supino');

    await userEvent.selectOptions(screen.getByLabelText('Grupo muscular'), 'PEITO');
    await userEvent.click(screen.getByLabelText('Mostrar inativos'));

    const ultimo = api.de('GET /api/exercicios').at(-1)!;
    expect(ultimo.busca.get('grupoMuscular')).toBe('PEITO');
    expect(ultimo.busca.get('incluirInativos')).toBe('true');
  });

  it('novo exercício; nome repetido mostra o motivo da API', async () => {
    const api = apiFalsa({ 'GET /api/exercicios': json(200, []),
      'POST /api/exercicios': (p) => ((p.corpo as { nome: string }).nome === 'Supino'
        ? json(409, { detail: 'já existe um exercício ativo com esse nome' }) : json(201, supino)) });
    renderizar(<CatalogoDeExercicios />, { perfil: 'INSTRUTOR' });

    await userEvent.click(await screen.findByRole('button', { name: 'Novo exercício' }));
    const formulario = screen.getByRole('form', { name: 'Novo exercício' });
    await userEvent.type(screen.getAllByLabelText('Nome')[0], ' Supino ');
    await userEvent.selectOptions(formulario.querySelector('select')!, 'PEITO');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(api.de('POST /api/exercicios')[0].corpo).toEqual({ nome: 'Supino', grupoMuscular: 'PEITO', observacao: null });
    expect(await screen.findByRole('alert')).toHaveTextContent('já existe um exercício ativo com esse nome');
  });

  it('editar manda a versão lida', async () => {
    const api = apiFalsa({ 'GET /api/exercicios': json(200, [supino]), 'GET /api/exercicios/e1': json(200, supino, { ETag: '"2"' }),
      'PUT /api/exercicios/e1': json(200, supino) });
    renderizar(<CatalogoDeExercicios />, { perfil: 'INSTRUTOR' });

    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));
    await userEvent.type(await screen.findByLabelText('Observação (como executar)'), 'Pegada aberta');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    const put = api.de('PUT /api/exercicios/e1')[0];
    expect(put.cabecalhos.get('If-Match')).toBe('"2"');
    expect(put.corpo).toEqual({ nome: 'Supino', grupoMuscular: 'PEITO', observacao: 'Pegada aberta' });
  });

  it('inativar e reativar', async () => {
    const api = apiFalsa({ 'GET /api/exercicios': json(200, [supino, { ...supino, id: 'e2', nome: 'Remada', ativo: false }]),
      'POST /api/exercicios/e1/inativar': json(200, {}), 'POST /api/exercicios/e2/reativar': json(200, {}) });
    renderizar(<CatalogoDeExercicios />, { perfil: 'INSTRUTOR' });

    await userEvent.click(await screen.findByRole('button', { name: 'Inativar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Reativar' }));

    expect(api.de('POST /api/exercicios/e1/inativar')).toHaveLength(1);
    expect(api.de('POST /api/exercicios/e2/reativar')).toHaveLength(1);
  });

  it.each(['INSTRUTOR', 'ADMIN'] as const)('excluir: o %s confirma e manda o DELETE (ADR 0047)', async (perfil) => {
    let excluido = false;
    const api = apiFalsa({ 'GET /api/exercicios': () => json(200, excluido ? [] : [supino]),
      'DELETE /api/exercicios/e1': () => { excluido = true; return new Response(null, { status: 204 }); } });
    renderizar(<CatalogoDeExercicios />, { perfil });
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(api.de('DELETE /api/exercicios/e1')).toHaveLength(1);
    expect(await screen.findByText('Nenhum exercício.')).toBeInTheDocument();
  });

  it('excluir exercício em fichas: mostra o motivo', async () => {
    apiFalsa({ 'GET /api/exercicios': json(200, [supino]),
      'DELETE /api/exercicios/e1': json(409, { title: 'Exercício em fichas', detail: 'O exercício já está em fichas e não pode ser excluído; inative-o' }) });
    renderizar(<CatalogoDeExercicios />, { perfil: 'ADMIN' });
    await userEvent.click(await screen.findByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));

    expect(await screen.findByText(/já está em fichas/)).toBeInTheDocument();
  });
});
