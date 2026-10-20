-- Cobrança (ADR 0024): a configuração de atraso da academia, as cobranças de cada matrícula
-- e, por matrícula, desde quando ela tem cobranças no sistema.

-- Uma linha só: a configuração vale para todas as cobranças, no dia do pagamento.
CREATE TABLE configuracao_cobranca (
    id                 SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    tipo_acrescimo     VARCHAR(10)  NOT NULL CHECK (tipo_acrescimo IN ('PERCENTUAL', 'VALOR')),
    -- Percentual de 0 a 2 (o teto do CDC); em reais, de 0 a 1.000 (e cortado em 2% ao cobrar).
    acrescimo          NUMERIC(6,2) NOT NULL CHECK (acrescimo >= 0 AND acrescimo <= 1000
                                                    AND (tipo_acrescimo = 'VALOR' OR acrescimo <= 2)),
    juros_ao_mes       NUMERIC(4,2) NOT NULL CHECK (juros_ao_mes >= 0 AND juros_ao_mes <= 10),
    dias_para_bloquear INT          NOT NULL CHECK (dias_para_bloquear BETWEEN 0 AND 90),
    versao             BIGINT       NOT NULL DEFAULT 0
);
-- Os valores decididos pelo dono: 2% de acréscimo, 1% ao mês de juros, bloqueio após 10 dias.
INSERT INTO configuracao_cobranca (tipo_acrescimo, acrescimo, juros_ao_mes, dias_para_bloquear)
VALUES ('PERCENTUAL', 2, 1, 10);

-- As matrículas que já existiam ganham cobranças só do dia em que foram vistas em diante.
CREATE TABLE cronogramas (
    matricula_id UUID PRIMARY KEY REFERENCES matriculas (id),
    a_partir_de  DATE NOT NULL
);

CREATE TABLE cobrancas (
    id                  UUID          PRIMARY KEY,
    matricula_id        UUID          NOT NULL REFERENCES matriculas (id),
    aluno_id            UUID          NOT NULL REFERENCES alunos (id),
    tipo                VARCHAR(12)   NOT NULL CHECK (tipo IN ('MENSALIDADE', 'A_VISTA', 'MULTA')),
    parcela             INT           NOT NULL CHECK (parcela >= 1),
    valor               NUMERIC(10,2) NOT NULL CHECK (valor > 0),
    vencimento          DATE          NOT NULL,
    status              VARCHAR(10)   NOT NULL CHECK (status IN ('PENDENTE', 'PAGA', 'CANCELADA')),
    -- O pagamento, com as partes do devido naquele dia.
    pago_acrescimo      NUMERIC(10,2),
    pago_juros          NUMERIC(10,2),
    pago_total          NUMERIC(10,2),
    meio_de_pagamento   VARCHAR(15)   CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO',
                                                                   'CARTAO_CREDITO')),
    paga_em             TIMESTAMPTZ,
    paga_por            UUID          REFERENCES usuarios (id),
    -- Sem quem cancelou: foi o sistema, ao encerrar a matrícula.
    cancelada_em        TIMESTAMPTZ,
    cancelada_por       UUID          REFERENCES usuarios (id),
    motivo_cancelamento VARCHAR(500),
    versao              BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_cobrancas_parcela UNIQUE (matricula_id, tipo, parcela),
    -- Paga se, e só se, tem o pagamento inteiro; IS NOT NULL nunca dá NULL (ver CLAUDE.md).
    CONSTRAINT ck_cobrancas_pagamento CHECK ((status = 'PAGA') = (
        pago_acrescimo IS NOT NULL AND pago_juros IS NOT NULL AND pago_total IS NOT NULL
        AND meio_de_pagamento IS NOT NULL AND paga_em IS NOT NULL AND paga_por IS NOT NULL)),
    CONSTRAINT ck_cobrancas_total CHECK (pago_total IS NULL OR (pago_acrescimo >= 0 AND pago_juros >= 0
        AND pago_total = valor + pago_acrescimo + pago_juros)),
    CONSTRAINT ck_cobrancas_cancelamento CHECK ((status = 'CANCELADA') = (
        cancelada_em IS NOT NULL AND motivo_cancelamento IS NOT NULL AND btrim(motivo_cancelamento) <> ''))
);
CREATE INDEX ix_cobrancas_aluno ON cobrancas (aluno_id, vencimento);
-- As em aberto por vencimento: a lista de atrasadas e o "pode treinar?".
CREATE INDEX ix_cobrancas_pendentes ON cobrancas (vencimento) WHERE status = 'PENDENTE';

-- A trilha passa a registrar cobranças e a configuração.
ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO'));
