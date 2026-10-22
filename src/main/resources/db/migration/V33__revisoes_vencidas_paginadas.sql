-- Lista das revisões vencidas paginada no banco (diagnóstico da etapa 7, T3). Ela deixa de
-- fora os alunos inativos, e o módulo de treinos não lê a tabela de alunos (ADR 0002):
-- filtrar depois de paginar deixaria páginas pela metade. A ficha atual guarda se o aluno
-- está ativo; o módulo de alunos avisa os treinos ao inativar e ao reativar, na mesma
-- transação (AoMudarASituacaoDoAluno). Nas encerradas o valor fica como estava: não se usa.
ALTER TABLE fichas ADD COLUMN aluno_ativo BOOLEAN NOT NULL DEFAULT true;

-- As atuais de quem já está inativo. Nenhuma encerrada muda (o gatilho da V32 recusaria).
UPDATE fichas f SET aluno_ativo = false
FROM alunos a
WHERE a.id = f.aluno_id AND NOT a.ativo AND f.encerrada_em IS NULL;

DROP INDEX ix_fichas_revisao;
CREATE INDEX ix_fichas_revisao ON fichas (revisao_em, montada_em, id)
    WHERE encerrada_em IS NULL AND revisao_em IS NOT NULL AND aluno_ativo;
