import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { CobrancasAtrasadas } from './CobrancasAtrasadas';

const pagina = (conteudo: object[]) => json(200, { conteudo, pagina: 0, tamanho: 20, totalElementos: conteudo.length, totalPaginas: 1 });

describe('cobranças atrasadas', () => {
  it('mostra o aluno pelo nome, com link para receber, e o devido de hoje', async () => {
    apiFalsa({ 'GET /api/cobrancas/atrasadas': pagina([{ id: 'c1', alunoId: 'a1', alunoNome: 'Bia', tipo: 'MENSALIDADE',
      parcela: 3, valor: 100, vencimento: '2026-10-01', situacao: 'ATRASADA', diasDeAtraso: 1,
      devidoHoje: { valor: 100, acrescimo: 2, juros: 0.03, total: 102.03 } }]) });

    renderizar(<CobrancasAtrasadas />);

    const link = await screen.findByRole('link', { name: /Bia/ });
    expect(link).toHaveAttribute('href', '/alunos/a1');
    expect(link).toHaveTextContent('Mensalidade 3 · venceu em 01/10/2026');
    expect(link).toHaveTextContent('1 dia');
    expect(link).toHaveTextContent(/R\$\s102,03/);
  });

  it('nenhuma atrasada: diz isso', async () => {
    apiFalsa({ 'GET /api/cobrancas/atrasadas': pagina([]) });

    renderizar(<CobrancasAtrasadas />);

    expect(await screen.findByText('Nenhuma cobrança atrasada.')).toBeInTheDocument();
  });
});
