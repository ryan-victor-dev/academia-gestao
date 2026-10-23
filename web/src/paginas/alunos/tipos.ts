export interface AlunoNaLista {
  id: string;
  nome: string;
  dataNascimento: string | null;
  ativo: boolean;
}

/** O cadastro completo (recepção e ADMIN). */
export interface Aluno {
  id: string;
  nome: string;
  cpf: string;
  email: string | null;
  telefone: string | null;
  dataNascimento: string | null;
  observacoes: string | null;
  ativo: boolean;
  criadoEm: string;
}

/** O que o professor vê: sem CPF, e-mail e telefone (LGPD, minimização). */
export type AlunoParaInstrutor = Pick<Aluno, 'id' | 'nome' | 'dataNascimento' | 'observacoes' | 'ativo'>;

export interface DadosDoAluno {
  nome: string;
  cpf: string;
  email: string;
  telefone: string;
  dataNascimento: string;
  observacoes: string;
}

export const ROTULOS_DO_ALUNO: Record<string, string> = {
  nome: 'Nome', cpf: 'CPF', email: 'E-mail', telefone: 'Telefone', dataNascimento: 'Nascimento',
  observacoes: 'Observações',
};

/** Campo vazio vai como null (a API guarda "sem e-mail", e não um texto vazio). */
export function paraEnviar(dados: DadosDoAluno) {
  const ou = (v: string) => (v.trim() === '' ? null : v.trim());
  return {
    nome: dados.nome.trim(), cpf: dados.cpf.trim(), email: ou(dados.email), telefone: ou(dados.telefone),
    dataNascimento: ou(dados.dataNascimento), observacoes: ou(dados.observacoes),
  };
}
