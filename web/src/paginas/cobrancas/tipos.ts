export type MeioDoBalcao = 'DINHEIRO' | 'PIX' | 'CARTAO_DEBITO' | 'CARTAO_CREDITO';
// O online (Pix, boleto e cartão pelo app) chega pela empresa de pagamentos, nunca pelo balcão (ADRs 0034 e 0037).
export type Meio = MeioDoBalcao | 'PIX_ONLINE' | 'BOLETO' | 'CREDITO_ONLINE' | 'DEBITO_ONLINE';
export type SituacaoDaCobranca = 'PENDENTE' | 'ATRASADA' | 'PAGA' | 'CANCELADA';

export interface Devido { valor: number; acrescimo: number; juros: number; total: number }

export interface Cobranca {
  id: string;
  alunoId: string;
  tipo: 'MENSALIDADE' | 'A_VISTA' | 'MULTA';
  parcela: number;
  valor: number;
  vencimento: string;
  prazo: string;
  situacao: SituacaoDaCobranca;
  diasDeAtraso: number;
  devidoHoje: Devido | null;
  pagamento: { total: number; acrescimo: number; juros: number; meio: Meio; codigoTransacao?: string | null; em: string } | null;
  cancelamento: { em: string; motivo: string } | null;
}

export interface CobrancaAtrasada extends Cobranca {
  alunoNome: string | null;
}

export interface Lancamento {
  id: string;
  tipo: 'PAGAMENTO' | 'ESTORNO';
  total: number;
  meio: Meio;
  codigoTransacao?: string | null;
  em: string;
  motivo: string | null;
}

export const NOME_DO_MEIO_DO_BALCAO: Record<MeioDoBalcao, string> = {
  DINHEIRO: 'Dinheiro', PIX: 'Pix', CARTAO_DEBITO: 'Cartão de débito', CARTAO_CREDITO: 'Cartão de crédito',
};

/** Pix e cartão do balcão levam o código da transação do comprovante (ADR 0046, item 10). */
export function exigeCodigo(meio: string): boolean {
  return meio === 'PIX' || meio === 'CARTAO_DEBITO' || meio === 'CARTAO_CREDITO';
}

export const NOME_DO_MEIO: Record<Meio, string> = {
  ...NOME_DO_MEIO_DO_BALCAO, PIX_ONLINE: 'Pix pelo app', BOLETO: 'Boleto pelo app',
  CREDITO_ONLINE: 'Crédito pelo app', DEBITO_ONLINE: 'Débito pelo app',
};

export const NOME_DA_SITUACAO: Record<SituacaoDaCobranca, string> = {
  PENDENTE: 'Pendente', ATRASADA: 'Atrasada', PAGA: 'Paga', CANCELADA: 'Cancelada',
};

export function descricao(c: Pick<Cobranca, 'tipo' | 'parcela'>): string {
  if (c.tipo === 'MENSALIDADE') return `Mensalidade ${c.parcela}`;
  if (c.tipo === 'A_VISTA') return 'Pagamento à vista';
  return 'Multa de cancelamento';
}
