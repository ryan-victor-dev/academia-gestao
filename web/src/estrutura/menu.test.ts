import { describe, expect, it } from 'vitest';
import { menuDo } from './menu';

const rotulos = (perfil: Parameters<typeof menuDo>[0]) => menuDo(perfil).map((i) => i.rotulo);

describe('menu por perfil (espelha as permissões da API)', () => {
  it('recepção: alunos, cobranças e caixa; nada de treino nem de administração', () => {
    expect(rotulos('RECEPCAO')).toEqual(['Início', 'Alunos', 'Cobranças', 'Caixa', 'Planos', 'Meus dados', 'Minha senha']);
  });

  it('professor: alunos (consulta), exercícios e revisões; nada de dinheiro', () => {
    expect(rotulos('INSTRUTOR')).toEqual(['Início', 'Alunos', 'Exercícios', 'Revisões', 'Planos', 'Meus dados', 'Minha senha']);
  });

  it('aluno: só o que é dele', () => {
    expect(rotulos('ALUNO')).toEqual(['Início', 'Meu treino', 'Pagamentos', 'Planos', 'Meus dados', 'Minha senha']);
  });

  it('ADMIN: tudo da equipe, mais equipe, configuração, auditoria e idades e gênero (ADR 0048); nada do aluno', () => {
    expect(rotulos('ADMIN')).toEqual(['Início', 'Alunos', 'Cobranças', 'Caixa', 'Exercícios', 'Revisões', 'Planos',
      'Equipe', 'Folha', 'Configuração', 'Pagamento online', 'Auditoria', 'Idades e gênero', 'Meus dados', 'Minha senha']);
  });
});
