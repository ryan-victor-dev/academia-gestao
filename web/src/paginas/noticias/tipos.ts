export type TipoDePublicacao = 'NOTICIA' | 'COMUNICADO' | 'EVENTO';

export const NOME_DO_TIPO: Record<TipoDePublicacao, string> = { NOTICIA: 'Notícia', COMUNICADO: 'Comunicado', EVENTO: 'Evento' };

export interface LinkDaPublicacao {
  url: string;
  rotulo: string | null;
}

/** Um anexo pronto: o endereço já vem assinado e vale por 1 a 2 horas (ADR 0047). */
export interface AnexoDaPublicacao {
  id: string;
  tipo: 'IMAGEM' | 'VIDEO' | 'DOCUMENTO';
  conteudoTipo: string;
  nome: string;
  tamanho: number;
  endereco: string;
}

/** Os tipos aceitos (a API confere pelo conteúdo) e os tetos de cada grupo. */
export const TIPOS_ACEITOS = 'image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,application/pdf';
export const MAXIMO_DE_ANEXOS = 10;

export interface Publicacao {
  id: string;
  tipo: TipoDePublicacao;
  titulo: string;
  texto: string;
  eventoInicio: string | null;
  eventoFim: string | null;
  eventoLocal: string | null;
  links: LinkDaPublicacao[];
  anexos: AnexoDaPublicacao[];
  criadaEm: string;
  autorNome: string;
  editadaEm: string | null;
}
