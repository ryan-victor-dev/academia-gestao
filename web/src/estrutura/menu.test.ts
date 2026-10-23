import { describe, expect, it } from 'vitest';
import { menuDo } from './menu';

const rotulos = (perfil: Parameters<typeof menuDo>[0]) => menuDo(perfil).map((i) => i.rotulo);

describe('menu por perfil (espelha as permissões da API)', () => {
  it('recepção: alunos, cobranças e caixa; nada de treino nem de administração', () => {
    expect(rotulos('RECEPCAO')).toEqual(['Início', 'Alunos', 'Cobranças', 'Caixa', 'Planos', 'Minha senha']);
  });

  it('professor: alunos (consulta), exercícios e revisões; nada de dinheiro', () => {
    expect(rotulos('INSTRUTOR')).toEqual(['Início', 'Alunos', 'Exercícios', 'Revisões', 'Planos', 'Minha senha']);
  });

  it('aluno: só o que é dele', () => {
    expect(rotulos('ALUNO')).toEqual(['Início', 'Meu treino', 'Pagamentos', 'Planos', 'Minha senha']);
  });

  it('ADMIN: tudo da equipe, mais equipe, configuração e auditoria; nada do aluno', () => {
    expect(rotulos('ADMIN')).toEqual(['Início', 'Alunos', 'Cobranças', 'Caixa', 'Exercícios', 'Revisões', 'Planos',
      'Equipe', 'Configuração', 'Auditoria', 'Minha senha']);
  });
});
