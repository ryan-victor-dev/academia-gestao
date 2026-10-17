-- Troca de plano no meio do período (ADR 0018): a matrícula atual termina na véspera e uma
-- nova começa no dia da troca, com nome, preço e regras do novo plano.

-- A multa também pode vir de uma troca (com fidelidade, para um plano que não a mantém).
ALTER TABLE matriculas RENAME COLUMN multa_cancelamento TO multa;

ALTER TABLE matriculas
    -- Na matrícula trocada: o dia da troca (ela vale até a véspera) e quem fez.
    ADD COLUMN trocada_em  DATE,
    ADD COLUMN trocada_por UUID REFERENCES usuarios (id),
    -- Na matrícula nova: de qual ela veio. Fica na nova porque a antiga é gravada primeiro
    -- (encurtada, para a nova não esbarrar no período dela) e a nova ainda não existe.
    ADD COLUMN troca_de    UUID UNIQUE REFERENCES matriculas (id),
    ADD CONSTRAINT ck_matriculas_troca CHECK ((trocada_em IS NULL) = (trocada_por IS NULL)),
    ADD CONSTRAINT ck_matriculas_trocada_ou_cancelada CHECK (trocada_em IS NULL OR cancelada_em IS NULL),
    ADD CONSTRAINT ck_matriculas_dia_da_troca CHECK (trocada_em IS NULL OR trocada_em BETWEEN inicio AND fim);

-- O período ocupado passa a ser o real: a trocada vale só até a véspera da troca. Com o
-- limite superior aberto ('[)'), trocar no próprio dia do início dá um período vazio, que
-- não se sobrepõe a nada.
ALTER TABLE matriculas DROP CONSTRAINT ex_matriculas_periodo_sobreposto;
ALTER TABLE matriculas ADD CONSTRAINT ex_matriculas_periodo_sobreposto
    EXCLUDE USING gist (aluno_id WITH =, daterange(inicio, COALESCE(trocada_em, fim + 1), '[)') WITH &&)
    WHERE (cancelada_em IS NULL);
