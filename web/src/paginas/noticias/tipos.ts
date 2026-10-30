export type TipoDePublicacao = 'NOTICIA' | 'COMUNICADO' | 'EVENTO';

export const NOME_DO_TIPO: Record<TipoDePublicacao, string> = { NOTICIA: 'Notícia', COMUNICADO: 'Comunicado', EVENTO: 'Evento' };

export interface LinkDaPublicacao {
  url: string;
  rotulo: string | null;
}

export interface Publicacao {
  id: string;
  tipo: TipoDePublicacao;
  titulo: string;
  texto: string;
  eventoInicio: string | null;
  eventoFim: string | null;
  eventoLocal: string | null;
  links: LinkDaPublicacao[];
  criadaEm: string;
  autorNome: string;
  editadaEm: string | null;
}
