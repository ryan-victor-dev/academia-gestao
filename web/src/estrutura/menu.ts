import type { Perfil } from '../sessao/api';

export interface ItemDoMenu {
  caminho: string;
  rotulo: string;
  perfis: Perfil[];
}

/**
 * O que cada perfil vê no menu, espelhando as permissões da API (README, tabela de rotas).
 * Esconder um item é só conveniência: quem protege é a API, que responde 403.
 */
export const MENU: ItemDoMenu[] = [
  { caminho: '/', rotulo: 'Início', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO'] },
  { caminho: '/alunos', rotulo: 'Alunos', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR'] },
  { caminho: '/cobrancas', rotulo: 'Cobranças', perfis: ['ADMIN', 'RECEPCAO'] },
  { caminho: '/caixa', rotulo: 'Caixa', perfis: ['ADMIN', 'RECEPCAO'] },
  { caminho: '/exercicios', rotulo: 'Exercícios', perfis: ['ADMIN', 'INSTRUTOR'] },
  { caminho: '/revisoes', rotulo: 'Revisões', perfis: ['ADMIN', 'INSTRUTOR'] },
  { caminho: '/meu-treino', rotulo: 'Meu treino', perfis: ['ALUNO'] },
  { caminho: '/minhas-cobrancas', rotulo: 'Pagamentos', perfis: ['ALUNO'] },
  { caminho: '/planos', rotulo: 'Planos', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO'] },
  { caminho: '/equipe', rotulo: 'Equipe', perfis: ['ADMIN'] },
  { caminho: '/folha', rotulo: 'Folha', perfis: ['ADMIN'] },
  { caminho: '/configuracao', rotulo: 'Configuração', perfis: ['ADMIN'] },
  { caminho: '/pagamento-online', rotulo: 'Pagamento online', perfis: ['ADMIN'] },
  { caminho: '/auditoria', rotulo: 'Auditoria', perfis: ['ADMIN'] },
  { caminho: '/idades-e-genero', rotulo: 'Idades e gênero', perfis: ['ADMIN'] },
  { caminho: '/meus-dados', rotulo: 'Meus dados', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO'] },
  { caminho: '/senha', rotulo: 'Minha senha', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO'] },
];

export const menuDo = (perfil: Perfil) => MENU.filter((item) => item.perfis.includes(perfil));

export const NOME_DO_PERFIL: Record<Perfil, string> = {
  ADMIN: 'Administração',
  RECEPCAO: 'Recepção',
  INSTRUTOR: 'Professor',
  ALUNO: 'Aluno',
};
