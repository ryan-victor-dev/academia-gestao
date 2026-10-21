-- Troca de plano (decisão do dono, ADR 0026): a matrícula nova começa no dia da troca, mas as
-- parcelas e os meses do plano contam do próximo vencimento da anterior, que já pagou até lá.
-- Vazio = o início (todas as de antes).
ALTER TABLE matriculas ADD COLUMN primeiro_vencimento DATE;
ALTER TABLE matriculas ADD CONSTRAINT ck_matriculas_primeiro_vencimento CHECK (
    primeiro_vencimento IS NULL
    OR (troca_de IS NOT NULL AND primeiro_vencimento > inicio
        AND primeiro_vencimento <= (inicio + interval '1 month')::date));

ALTER TABLE matriculas DROP CONSTRAINT ck_matriculas_fim;
ALTER TABLE matriculas ADD CONSTRAINT ck_matriculas_fim
    CHECK (fim = (COALESCE(primeiro_vencimento, inicio) + make_interval(months => duracao_meses))::date - 1
                 + dias_trancados);
