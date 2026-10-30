import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { apiFalsa, json, renderizar } from '../../testes/ajudantes';
import { tamanhoLegivel } from './Anexos';
import { Mural } from './Mural';
import type { AnexoDaPublicacao, Publicacao } from './tipos';

const anexo = (id: string, tipo: AnexoDaPublicacao['tipo'], nome: string, tamanho = 2048): AnexoDaPublicacao =>
  ({ id, tipo, conteudoTipo: '', nome, tamanho, endereco: `/api/anexos/${id}?expira=1&assinatura=x` });
const publicacao = (anexos: AnexoDaPublicacao[]): Publicacao => ({
  id: 'p1', tipo: 'NOTICIA', titulo: 'Festa junina', texto: 'Fotos da festa', eventoInicio: null, eventoFim: null,
  eventoLocal: null, links: [], anexos, criadaEm: '2026-10-30T15:00:00Z', autorNome: 'Rita', editadaEm: null,
});
const pagina = (conteudo: Publicacao[]) => ({ conteudo, pagina: 0, tamanho: 10, totalElementos: conteudo.length, totalPaginas: 1 });
const arquivo = (nome: string, tipo: string) => new File(['conteúdo'], nome, { type: tipo });

function abrir(perfil: 'ALUNO' | 'RECEPCAO', anexos: AnexoDaPublicacao[], rotas: Parameters<typeof apiFalsa>[0] = {}) {
  const api = apiFalsa({ 'GET /api/publicacoes': json(200, pagina([publicacao(anexos)])),
    'GET /api/publicacoes/proximos-eventos': json(200, []), ...rotas });
  renderizar(<Mural />, { perfil });
  return api;
}

describe('anexos das publicações (ADR 0047)', () => {
  it('foto e vídeo aparecem na página, pelo endereço assinado; o documento é download', async () => {
    abrir('ALUNO', [anexo('a1', 'IMAGEM', 'quadrilha.jpg'), anexo('a2', 'VIDEO', 'danca.mp4'), anexo('a3', 'DOCUMENTO', 'regulamento.pdf', 3 * 1024 * 1024)]);

    expect(await screen.findByRole('img', { name: 'quadrilha.jpg' })).toHaveAttribute('src', '/api/anexos/a1?expira=1&assinatura=x');
    const video = screen.getByLabelText('danca.mp4');
    expect(video.tagName).toBe('VIDEO');
    expect(video).toHaveAttribute('src', '/api/anexos/a2?expira=1&assinatura=x');
    const documento = screen.getByRole('link', { name: 'regulamento.pdf (3 MB)' });
    expect(documento).toHaveAttribute('href', '/api/anexos/a3?expira=1&assinatura=x');
    expect(documento).toHaveAttribute('download', 'regulamento.pdf');
    expect(screen.queryByText(/Adicionar fotos/)).not.toBeInTheDocument();
  });

  it('publicação nova: publica e depois manda cada arquivo, cru, com o nome no cabeçalho', async () => {
    const api = abrir('RECEPCAO', [], {
      'POST /api/publicacoes': json(201, { ...publicacao([]), id: 'nova' }),
      'POST /api/publicacoes/nova/anexos': json(201, anexo('x', 'IMAGEM', 'x.jpg')),
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    await userEvent.type(screen.getByLabelText('Título'), 'Festa');
    await userEvent.type(screen.getByLabelText('Texto'), 'Fotos');
    const foto = arquivo('festa ção.jpg', 'image/jpeg');
    const pdf = arquivo('lista.pdf', 'application/pdf');
    await userEvent.upload(screen.getByLabelText('Fotos, vídeos ou documentos (opcional)'), [foto, pdf]);
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    await screen.findByRole('button', { name: 'Nova publicação' });
    const envios = api.de('POST /api/publicacoes/nova/anexos');
    expect(envios).toHaveLength(2);
    expect(envios[0].corpo).toBe(foto);
    expect(envios[0].cabecalhos.get('X-Nome-Do-Arquivo')).toBe('festa%20%C3%A7%C3%A3o.jpg');
    expect(envios[0].cabecalhos.get('Content-Type')).toBe('image/jpeg');
    expect(envios[1].corpo).toBe(pdf);
    expect(api.de('POST /api/publicacoes')[0].cabecalhos.get('Content-Type')).toBe('application/json');
  });

  it('um anexo recusado: o erro aparece, e os seguintes não vão', async () => {
    const api = abrir('RECEPCAO', [], {
      'POST /api/publicacoes': json(201, { ...publicacao([]), id: 'nova' }),
      'POST /api/publicacoes/nova/anexos': json(415, { detail: 'Envie foto (JPEG, PNG, WebP ou GIF), vídeo (MP4 ou WebM) ou documento PDF' }),
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova publicação' }));
    await userEvent.type(screen.getByLabelText('Título'), 'Festa');
    await userEvent.type(screen.getByLabelText('Texto'), 'Fotos');
    await userEvent.upload(screen.getByLabelText('Fotos, vídeos ou documentos (opcional)'), [arquivo('a.jpg', 'image/jpeg'), arquivo('b.jpg', 'image/jpeg')]);
    await userEvent.click(screen.getByRole('button', { name: 'Publicar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Envie foto');
    expect(api.de('POST /api/publicacoes/nova/anexos')).toHaveLength(1);
  });

  it('na edição: tira um anexo e manda mais', async () => {
    const api = abrir('RECEPCAO', [anexo('a1', 'IMAGEM', 'velha.jpg')], {
      'DELETE /api/publicacoes/p1/anexos/a1': new Response(null, { status: 204 }),
      'POST /api/publicacoes/p1/anexos': json(201, anexo('a2', 'VIDEO', 'novo.mp4')),
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));
    const gerenciar = screen.getByText(/Fotos até 5 MB/).closest('.gerenciar-anexos') as HTMLElement;
    await userEvent.click(within(gerenciar).getByRole('button', { name: 'Excluir' }));
    await userEvent.click(within(gerenciar).getByRole('button', { name: 'Sim, excluir' }));
    expect(api.de('DELETE /api/publicacoes/p1/anexos/a1')).toHaveLength(1);

    await userEvent.upload(screen.getByLabelText('Adicionar fotos, vídeos ou documentos'), arquivo('novo.mp4', 'video/mp4'));
    expect(api.de('POST /api/publicacoes/p1/anexos')).toHaveLength(1);
    expect(api.de('GET /api/publicacoes').length).toBeGreaterThanOrEqual(3);
  });

  it('com 10 anexos, não oferece mais', async () => {
    abrir('RECEPCAO', Array.from({ length: 10 }, (_, i) => anexo(`a${i}`, 'IMAGEM', `f${i}.jpg`)));
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }));
    expect(screen.queryByLabelText('Adicionar fotos, vídeos ou documentos')).not.toBeInTheDocument();
  });

  it('tamanho legível', () => {
    expect(tamanhoLegivel(300 * 1024)).toBe('300 KB');
    expect(tamanhoLegivel(100)).toBe('1 KB');
    expect(tamanhoLegivel(2.5 * 1024 * 1024)).toBe('2,5 MB');
  });
});
