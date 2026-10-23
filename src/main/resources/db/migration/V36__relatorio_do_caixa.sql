-- Relatório do caixa (ADR 0031). Os lançamentos com caixa entram pelo dia do caixa (índice
-- da V35, por caixa_id); os de antes do caixa, sem ele, pelo dia do registro. Estes são
-- poucos e não crescem mais: um índice parcial só com eles.
CREATE INDEX ix_lancamentos_sem_caixa ON lancamentos (registrado_em) WHERE caixa_id IS NULL;
