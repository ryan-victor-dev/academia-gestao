-- Histórico de um aluno, plano ou usuário pelo id (diagnóstico de 2026-10-18): o índice da
-- V18 começa pela entidade, e a consulta só com entidadeId não conseguia usá-lo. Com um
-- milhão de registros, cada consulta varria a tabela inteira (1,6 s com 16 clientes).
-- O da V18 continua servindo à listagem só por entidade.
CREATE INDEX ix_auditoria_entidade_id ON auditoria (entidade_id, id DESC);
