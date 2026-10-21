-- Troca de plano (decisão do dono, ADR 0026): o que o aluno pagou à matrícula anterior e não
-- usou (o à vista trocado no meio) vira crédito na nova, abatido das primeiras parcelas. Vazio =
-- sem crédito. Crédito maior que o plano novo inteiro: a troca é recusada.
ALTER TABLE matriculas ADD COLUMN credito NUMERIC(10,2);
ALTER TABLE matriculas ADD CONSTRAINT ck_matriculas_credito CHECK (
    credito IS NULL
    OR (troca_de IS NOT NULL AND credito > 0 AND credito <= valor_mensal * duracao_meses));
