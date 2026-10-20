-- Registro de pagamentos e estornos (ADR 0025). Antes, o estorno apagava o pagamento da linha
-- da cobrança, e ele só sobrava na trilha (sem as partes, e apagada depois de 5 anos). Aqui
-- nada se altera nem se apaga: o estorno é um lançamento novo, com os mesmos valores, que
-- aponta para o pagamento. A cobrança continua com o estado atual (o pagamento em vigor).
CREATE TABLE lancamentos (
    id                UUID          PRIMARY KEY,
    cobranca_id       UUID          NOT NULL REFERENCES cobrancas (id),
    tipo              VARCHAR(10)   NOT NULL CHECK (tipo IN ('PAGAMENTO', 'ESTORNO')),
    -- Só no estorno: o pagamento estornado, da mesma cobrança, uma vez só.
    estorna           UUID          UNIQUE,
    valor             NUMERIC(10,2) NOT NULL CHECK (valor > 0),
    acrescimo         NUMERIC(10,2) NOT NULL CHECK (acrescimo >= 0),
    juros             NUMERIC(12,2) NOT NULL CHECK (juros >= 0),
    total             NUMERIC(12,2) NOT NULL,
    meio_de_pagamento VARCHAR(15)   NOT NULL CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO',
                                                                         'CARTAO_CREDITO')),
    registrado_em     TIMESTAMPTZ   NOT NULL,
    registrado_por    UUID          NOT NULL REFERENCES usuarios (id),
    motivo            VARCHAR(500),

    CONSTRAINT uq_lancamentos_da_cobranca UNIQUE (id, cobranca_id),
    CONSTRAINT fk_lancamentos_estorno_da_mesma_cobranca FOREIGN KEY (estorna, cobranca_id)
        REFERENCES lancamentos (id, cobranca_id),
    CONSTRAINT ck_lancamentos_total CHECK (total = valor + acrescimo + juros),
    -- Estorno se, e só se, aponta para o pagamento e tem motivo; tipo é NOT NULL, então nunca dá NULL.
    CONSTRAINT ck_lancamentos_estorno CHECK ((tipo = 'ESTORNO') = (estorna IS NOT NULL)
        AND (tipo = 'ESTORNO') = (motivo IS NOT NULL AND btrim(motivo) <> ''))
);
CREATE INDEX ix_lancamentos_cobranca ON lancamentos (cobranca_id, registrado_em);

-- As cobranças já pagas entram com o pagamento em vigor. Os pagamentos já estornados antes
-- desta migração ficam só na trilha de auditoria.
INSERT INTO lancamentos (id, cobranca_id, tipo, valor, acrescimo, juros, total, meio_de_pagamento, registrado_em,
                         registrado_por)
SELECT gen_random_uuid(), id, 'PAGAMENTO', valor, pago_acrescimo, pago_juros, pago_total, meio_de_pagamento, paga_em,
       paga_por
FROM cobrancas WHERE status = 'PAGA';
