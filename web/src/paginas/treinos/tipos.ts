export type GrupoMuscular = 'PEITO' | 'COSTAS' | 'OMBROS' | 'BICEPS' | 'TRICEPS' | 'ANTEBRACO' | 'ABDOMEN' | 'QUADRICEPS'
  | 'POSTERIORES' | 'GLUTEOS' | 'PANTURRILHA' | 'CORPO_INTEIRO' | 'CARDIO';

export const NOME_DO_GRUPO: Record<GrupoMuscular, string> = {
  PEITO: 'Peito', COSTAS: 'Costas', OMBROS: 'Ombros', BICEPS: 'Bíceps', TRICEPS: 'Tríceps', ANTEBRACO: 'Antebraço',
  ABDOMEN: 'Abdômen', QUADRICEPS: 'Quadríceps', POSTERIORES: 'Posteriores', GLUTEOS: 'Glúteos',
  PANTURRILHA: 'Panturrilha', CORPO_INTEIRO: 'Corpo inteiro', CARDIO: 'Cardio',
};

export interface Exercicio {
  id: string;
  nome: string;
  grupoMuscular: GrupoMuscular;
  observacao: string | null;
  ativo: boolean;
}

export interface ItemDaFicha {
  exercicioId: string;
  nome: string;
  series: number;
  repeticoes: string;
  carga: string | null;
  descansoSegundos: number | null;
  observacao: string | null;
}

export interface Divisao {
  nome: string;
  exercicios: ItemDaFicha[];
}

export interface Ficha {
  id: string;
  alunoId: string;
  atual: boolean;
  montadaEm: string;
  revisaoEm: string | null;
  revisaoVencida?: boolean;
  corrigidaEm: string | null;
  encerradaEm: string | null;
  divisoes: Divisao[];
}

/** Limites da API (Ficha.java): a tela usa para não oferecer o que seria recusado. */
export const LIMITES = { divisoes: 7, exerciciosPorDivisao: 20, series: 20, descanso: 600 };
