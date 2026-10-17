-- Trancamento: o aluno pausa a matrícula (viagem, lesão) e o fim avança pelos dias parados,
-- para ele não perder o que pagou (ADR 0018).

-- Total de dias trancados da matrícula: no máximo 90.
ALTER TABLE matriculas
    ADD COLUMN dias_trancados INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_matriculas_dias_trancados CHECK (dias_trancados BETWEEN 0 AND 90);

-- O fim deixa de ser fixo: início + N meses - 1 dia + dias trancados (mesma conta do Java).
ALTER TABLE matriculas DROP CONSTRAINT ck_matriculas_fim;
ALTER TABLE matriculas ADD CONSTRAINT ck_matriculas_fim
    CHECK (fim = (inicio + make_interval(months => duracao_meses))::date - 1 + dias_trancados);

-- O aluno fica parado de "inicio" até a véspera da volta: volta_prevista, ou "volta" se
-- voltou antes. Volta no próprio dia do início desfaz o trancamento (0 dias).
CREATE TABLE trancamentos (
    id                   UUID         PRIMARY KEY,
    matricula_id         UUID         NOT NULL REFERENCES matriculas (id),
    inicio               DATE         NOT NULL,
    volta_prevista       DATE         NOT NULL,
    volta                DATE,
    motivo               VARCHAR(500) NOT NULL,
    criado_por           UUID         NOT NULL REFERENCES usuarios (id),
    criado_em            TIMESTAMPTZ  NOT NULL,
    volta_registrada_por UUID         REFERENCES usuarios (id),

    CONSTRAINT ck_trancamentos_motivo CHECK (btrim(motivo) <> ''),
    -- De 7 a 90 dias pedidos.
    CONSTRAINT ck_trancamentos_duracao CHECK (volta_prevista - inicio BETWEEN 7 AND 90),
    -- Volta antecipada: entre o início e a véspera da volta prevista, com quem registrou.
    CONSTRAINT ck_trancamentos_volta CHECK (volta IS NULL OR (volta >= inicio AND volta < volta_prevista)),
    CONSTRAINT ck_trancamentos_volta_registrada CHECK ((volta IS NULL) = (volta_registrada_por IS NULL)),
    -- Um trancamento por vez na mesma matrícula. Um desfeito (volta = início) é um período
    -- vazio, que não se sobrepõe a nada.
    CONSTRAINT ex_trancamentos_sobrepostos
        EXCLUDE USING gist (matricula_id WITH =, daterange(inicio, COALESCE(volta, volta_prevista), '[)') WITH &&)
);
