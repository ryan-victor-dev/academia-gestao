import type { Perfil } from '../../sessao/api';

export type Vinculo = 'CLT' | 'MEI' | 'AUTONOMO';

/**
 * Uma pessoa da folha (ADR 0049). Sem {@code id}: alguém da equipe com login que ainda não está na folha. Sem
 * {@code usuarioId}: quem trabalha sem usar o sistema.
 */
export interface PessoaDaFolha {
  id: string | null;
  usuarioId: string | null;
  nome: string;
  cpf: string | null;
  perfil: Exclude<Perfil, 'ALUNO'> | null;
  vinculo: Vinculo | null;
  valorCombinado: number | null;
  ativo: boolean;
  versao: number;
}

export const NOME_DO_VINCULO: Record<Vinculo, string> = { CLT: 'CLT', MEI: 'MEI', AUTONOMO: 'Autônomo' };
