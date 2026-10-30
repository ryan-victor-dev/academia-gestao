import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { Mural } from './Mural';
import type { Publicacao } from './tipos';

const publicacao = (mudancas: Partial<Publicacao> = {}): Publicacao => ({
  id: 'p1', tipo: 'NOTICIA', titulo: 'Aula nova de pilates', texto: 'Às terças.\n<b>Venha</b>', eventoInicio: null,
  eventoFim: null, eventoLocal: null, links: [{ url: 'https://academia.exemplo/pilates', rotulo: 'Horários' }],
  anexos: [], criadaEm: '2026-10-30T15:00:00Z', autorNome: 'Rita', editadaEm: null, ...mudancas,
});
const evento = publicacao({ id: 'e1', tipo: 'EVENTO', titulo: 'Aulão de sábado', eventoInicio: '2026-11-07T12:00:00Z',
  eventoFim: '2026-11-07T14:00:00Z', eventoLocal: 'Sala 2', links: [] });
const pagina = (conteudo: Publicacao[]) => ({ conteudo, pagina: 0, tamanho: 10, totalElementos: conteudo.length, totalPaginas: 1 });
const cartao = (titulo: string) => screen.getByRole('heading', { name: titulo }).closest('li')!;

function abrir(perfil: 'ALUNO' | 'RECEPCAO' | 'ADMIN' | 'INSTRUTOR', rotas: Parameters<typeof apiFalsa>[0] = {}) {
  const api = apiFalsa({ 'GET /api/publicacoes': (p) => json(200, pagina(p.busca.get('tipo') === 'EVENTO' ? [evento] : [publicacao(), evento])),
    'GET /api/publicacoes/proximos-eventos': json(200, [evento]), ...rotas });
  renderizar(<Mural />, { perfil });
  return api;
}

describe('notícias e eventos (ADR 0047)', () => {
  // O aparelho em outro fuso: a hora do evento tem de valer na academia, e não no aparelho.
  const fusoDoAparelho = process.env.TZ;
  beforeAll(() => { process.env.TZ = 'Asia/Tokyo'; });
  afterAll(() => { process.env.TZ = fusoDoAparelho; });

  it('o aluno lê: texto como texto, links que abrem fora, quando e onde do evento, sem botões de publicar', async () => {
    abrir('ALUNO');

    expect(await screen.findByRole('heading', { name: 'Aula nova de pilates' })).toBeInTheDocument();
    expect(within(cartao('Aula nova de pilates')).getByText(/<b>Venha<\/b>/)).toBeInTheDocument();
    const link = within(cartao('Aula nova de pilates')).getByRole('link', { name: 'Horários' });
    expect(link).toHaveAttribute('href', 'https://academia.exemplo/pilates');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(cartao('Aulão de sábado')).getByText(/Sala 2/)).toBeInTheDocument();
    expect(within(cartao('Aulão de sábado')).getByText('07/11/2026, 09:00')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Próximos eventos' })).toBeInTheDocument();
    expect(within(cartao('Aula nova de pilates')).getByText(/Publicado por Rita em 30\/10\/2026, 12:00/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Nova publicação' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument();
  });

  it('filtra pelo tipo', async () => {
    const api = abrir('INSTRUTOR');
    await screen.findByRole('heading', { name: 'Aula nova de pilates' });
    await userEvent.click(screen.getByRole('tab', { name: 'Eventos' }));
    await screen.findByRole('tab', { name: 'Eventos', selected: true });
    expect(api.de('GET /api/publicacoes').at(-1)!.busca.get('tipo')).toBe('EVENTO');
    expect(await screen.findByText('Aulão de sábado', { selector: 'h3' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Aula nova de pilates' })).not.toBeInTheDocument();
  });

  it('a recepção publica um evento: a hora vale na academia, e os links vão na ordem, sem o tirado', async () => {
    const api = abrir('RECEPCAO', { 'POST /api/publicacoes': json(201, evento) });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'EVENTO');
    await userEvent.type(screen.getByLabelText('Título'), 'Aulão');
    await userEvent.type(screen.getByLabelText('Texto'), 'Traga água');
    fireEvent.change(screen.getByLabelText('Começa em'), { target: { value: '2026-11-07T09:00' } });
    await userEvent.type(screen.getByLabelText('Local (opcional)'), 'Sala 2');
    for (let i = 0; i < 3; i++) await userEvent.click(screen.getByRole('button', { name: 'Adicionar link' }));
    await userEvent.type(screen.getByLabelText('Endereço 1'), 'https://mapa.exemplo');
    await userEvent.type(screen.getByLabelText('Endereço 2'), 'https://errado.exemplo');
    await userEvent.type(screen.getByLabelText('Endereço 3'), 'https://inscricao.exemplo');
    await userEvent.type(screen.getByLabelText('Nome do link 3 (opcional)'), 'Inscrição');
    await userEvent.click(screen.getByRole('button', { name: 'Tirar o link 2' }));
    expect(screen.getByLabelText('Endereço 2')).toHaveValue('https://inscricao.exemplo');
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    expect(api.de('POST /api/publicacoes')[0].corpo).toEqual({ tipo: 'EVENTO', titulo: 'Aulão', texto: 'Traga água',
      eventoInicio: '2026-11-07T12:00:00.000Z', eventoFim: null, eventoLocal: 'Sala 2',
      links: [{ url: 'https://mapa.exemplo', rotulo: null }, { url: 'https://inscricao.exemplo', rotulo: 'Inscrição' }], avisar: true });
    expect(api.de('GET /api/publicacoes').length).toBeGreaterThan(1);
  });

  it('notícia não manda data nem local', async () => {
    const api = abrir('ADMIN', { 'POST /api/publicacoes': json(201, publicacao()) });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    expect(screen.queryByLabelText('Começa em')).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Título'), 'Notícia');
    await userEvent.type(screen.getByLabelText('Texto'), 'Texto');
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));
    expect(screen.queryByLabelText(/Avisar alunos e equipe/)).not.toBeInTheDocument();
    expect(api.de('POST /api/publicacoes')[0].corpo).toEqual({ tipo: 'NOTICIA', titulo: 'Notícia', texto: 'Texto',
      eventoInicio: null, eventoFim: null, eventoLocal: null, links: [], avisar: false });
  });

  it('editar vem preenchido (a hora na academia) e manda o PUT; excluir confirma', async () => {
    const api = abrir('ADMIN', { 'PUT /api/publicacoes/e1': json(200, evento), 'DELETE /api/publicacoes/p1': new Response(null, { status: 204 }) });
    await screen.findByRole('heading', { name: 'Aulão de sábado' });
    await userEvent.click(within(cartao('Aulão de sábado')).getByRole('button', { name: 'Editar' }));
    expect(screen.getByLabelText('Começa em')).toHaveValue('2026-11-07T09:00');
    expect(screen.getByLabelText('Termina em (opcional)')).toHaveValue('2026-11-07T11:00');
    expect(screen.getByLabelText('Título')).toHaveValue('Aulão de sábado');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(api.de('PUT /api/publicacoes/e1')[0].corpo).toMatchObject({ tipo: 'EVENTO', eventoInicio: '2026-11-07T12:00:00.000Z',
      eventoFim: '2026-11-07T14:00:00.000Z', eventoLocal: 'Sala 2' });

    await screen.findByRole('heading', { name: 'Aula nova de pilates' });
    await userEvent.click(within(cartao('Aula nova de pilates')).getByRole('button', { name: 'Excluir' }));
    await userEvent.click(within(cartao('Aula nova de pilates')).getByRole('button', { name: 'Sim, excluir' }));
    expect(api.de('DELETE /api/publicacoes/p1')).toHaveLength(1);
  });

  it('a recusa da API aparece', async () => {
    abrir('RECEPCAO', { 'POST /api/publicacoes': json(400, { detail: 'links: endereço inválido (use http:// ou https://): javascript:x' }) });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    await userEvent.type(screen.getByLabelText('Título'), 'T');
    await userEvent.type(screen.getByLabelText('Texto'), 'X');
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('endereço inválido');
  });

  it('nada publicado: avisa', async () => {
    apiFalsa({ 'GET /api/publicacoes': json(200, pagina([])), 'GET /api/publicacoes/proximos-eventos': json(200, []) });
    renderizar(<Mural />, { perfil: 'ALUNO' });
    expect(await screen.findByText('Nada publicado ainda.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Próximos eventos' })).not.toBeInTheDocument();
  });

  it('comunicado: avisar vem marcado e dá para desmarcar; na edição não aparece', async () => {
    const api = abrir('RECEPCAO', { 'POST /api/publicacoes': json(201, publicacao()), 'PUT /api/publicacoes/e1': json(200, evento) });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    await userEvent.selectOptions(screen.getByLabelText('Tipo'), 'COMUNICADO');
    const avisar = screen.getByLabelText(/Avisar alunos e equipe por e-mail e SMS/);
    expect(avisar).toBeChecked();
    await userEvent.click(avisar);
    await userEvent.type(screen.getByLabelText('Título'), 'Feriado');
    await userEvent.type(screen.getByLabelText('Texto'), 'Fechados');
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));
    expect(api.de('POST /api/publicacoes')[0].corpo).toMatchObject({ tipo: 'COMUNICADO', avisar: false });

    await userEvent.click(within(cartao('Aulão de sábado')).getByRole('button', { name: 'Editar' }));
    expect(screen.queryByLabelText(/Avisar alunos e equipe/)).not.toBeInTheDocument();
  });
});
