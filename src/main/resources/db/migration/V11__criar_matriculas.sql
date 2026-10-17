-- btree_gist deixa um índice GiST comparar colunas comuns (aluno_id WITH =), o que a
-- restrição de exclusão abaixo precisa. Vem junto com o PostgreSQL (contrib), como o unaccent.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Matrícula: um aluno num plano por um período (ADR 0016). Valor e duração são COPIADOS
-- do plano no dia da matrícula: reajustar o plano depois não muda o que o aluno contratou
-- (ADR 0013). A situação (agendada, vigente, encerrada) é calculada pelas datas; só o
-- cancelamento é gravado.
CREATE TABLE matriculas (
    id                  UUID          PRIMARY KEY,
    aluno_id            UUID          NOT NULL REFERENCES alunos (id),
    plano_id            UUID          NOT NULL REFERENCES planos (id),
    valor_mensal        NUMERIC(10,2) NOT NULL CHECK (valor_mensal > 0),
    duracao_meses       INT           NOT NULL CHECK (duracao_meses BETWEEN 1 AND 60),
    inicio              DATE          NOT NULL,
    fim                 DATE          NOT NULL,
    criada_em           TIMESTAMPTZ   NOT NULL,
    cancelada_em        TIMESTAMPTZ,
    cancelada_por       UUID          REFERENCES usuarios (id),
    motivo_cancelamento VARCHAR(500),
    versao              BIGINT        NOT NULL DEFAULT 0,

    -- Fim = início + N meses - 1 dia, a mesma conta do Java. O PostgreSQL, como o
    -- plusMonths, cai no último dia do mês quando o dia não existe (31/01 + 1 mês = 28/02).
    CONSTRAINT ck_matriculas_fim
        CHECK (fim = (inicio + make_interval(months => duracao_meses))::date - 1),

    -- Cancelamento é tudo ou nada: quando, quem e por quê.
    CONSTRAINT ck_matriculas_cancelamento
        CHECK ((cancelada_em IS NULL) = (cancelada_por IS NULL)
           AND (cancelada_em IS NULL) = (motivo_cancelamento IS NULL)),
    CONSTRAINT ck_matriculas_motivo
        CHECK (btrim(motivo_cancelamento) <> ''),

    -- Os períodos das matrículas não canceladas de um aluno não se sobrepõem: no máximo
    -- uma valendo, mais a renovação agendada para depois do fim. Vale também numa corrida
    -- entre dois cadastros e para INSERT feito fora da aplicação.
    CONSTRAINT ex_matriculas_periodo_sobreposto
        EXCLUDE USING gist (aluno_id WITH =, daterange(inicio, fim, '[]') WITH &&)
        WHERE (cancelada_em IS NULL)
);

-- Histórico de um aluno (inclusive as canceladas, que o índice da restrição não cobre).
CREATE INDEX ix_matriculas_aluno ON matriculas (aluno_id, inicio);
