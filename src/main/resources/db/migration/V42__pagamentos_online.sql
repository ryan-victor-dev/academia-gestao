-- Pagamento online (ADR 0034): cada Pix ou boleto gerado para uma cobrança, com o valor
-- combinado no dia, e a configuração do ADMIN.

-- Uma linha só, como a da cobrança. Os valores iniciais aceitos pelo dono: Pix e boleto
-- ligados, o aluno paga as atrasadas e a próxima a vencer, e o boleto de uma cobrança vencida
-- vale 3 dias.
CREATE TABLE configuracao_pagamento_online (
    id             SMALLINT   PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    pix_ligado     BOOLEAN    NOT NULL,
    boleto_ligado  BOOLEAN    NOT NULL,
    adiantar       VARCHAR(7) NOT NULL CHECK (adiantar IN ('NENHUMA', 'PROXIMA', 'TODAS')),
    dias_do_boleto INT        NOT NULL CHECK (dias_do_boleto BETWEEN 1 AND 30),
    versao         BIGINT     NOT NULL DEFAULT 0
);
INSERT INTO configuracao_pagamento_online (pix_ligado, boleto_ligado, adiantar, dias_do_boleto)
VALUES (true, true, 'PROXIMA', 3);

CREATE TABLE pagamentos_online (
    id                     UUID          PRIMARY KEY,
    cobranca_id            UUID          NOT NULL REFERENCES cobrancas (id),
    aluno_id               UUID          NOT NULL REFERENCES alunos (id),
    forma                  VARCHAR(6)    NOT NULL CHECK (forma IN ('PIX', 'BOLETO')),
    -- A empresa (o nome no .env) e o id do pagamento nela.
    provedor               VARCHAR(30)   NOT NULL,
    id_externo             VARCHAR(100)  NOT NULL,
    -- O combinado no dia em que foi gerado, em partes (ADR 0034).
    valor                  NUMERIC(10,2) NOT NULL CHECK (valor > 0),
    acrescimo              NUMERIC(10,2) NOT NULL CHECK (acrescimo >= 0),
    juros                  NUMERIC(12,2) NOT NULL CHECK (juros >= 0),
    pix_copia_e_cola       VARCHAR(1000),
    boleto_linha_digitavel VARCHAR(60),
    boleto_url             VARCHAR(500),
    valido_ate             TIMESTAMPTZ   NOT NULL,
    gerado_em              TIMESTAMPTZ   NOT NULL,
    gerado_por             UUID          NOT NULL REFERENCES usuarios (id),
    situacao               VARCHAR(10)   NOT NULL CHECK (situacao IN ('AGUARDANDO', 'PAGO', 'EXPIRADO', 'CANCELADO',
                                                                      'A_DEVOLVER', 'DEVOLVIDO')),
    recebido               NUMERIC(12,2) CHECK (recebido > 0),
    recebido_em            TIMESTAMPTZ,
    motivo_da_devolucao    VARCHAR(500),
    devolvido_em           TIMESTAMPTZ,
    devolvido_por          UUID          REFERENCES usuarios (id),

    CONSTRAINT uq_pagamentos_online_externo UNIQUE (provedor, id_externo),
    -- O Pix tem o copia e cola; o boleto, a linha digitável. IS NOT NULL nunca dá NULL (ver CLAUDE.md).
    CONSTRAINT ck_pagamentos_online_dados CHECK (CASE forma WHEN 'PIX' THEN pix_copia_e_cola IS NOT NULL
                                                           ELSE boleto_linha_digitavel IS NOT NULL END),
    CONSTRAINT ck_pagamentos_online_recebido CHECK ((situacao IN ('PAGO', 'A_DEVOLVER', 'DEVOLVIDO'))
        = (recebido IS NOT NULL AND recebido_em IS NOT NULL)),
    CONSTRAINT ck_pagamentos_online_motivo CHECK ((situacao IN ('A_DEVOLVER', 'DEVOLVIDO'))
        = (motivo_da_devolucao IS NOT NULL AND btrim(motivo_da_devolucao) <> '')),
    CONSTRAINT ck_pagamentos_online_devolvido CHECK ((situacao = 'DEVOLVIDO')
        = (devolvido_em IS NOT NULL AND devolvido_por IS NOT NULL))
);
CREATE INDEX ix_pagamentos_online_cobranca ON pagamentos_online (cobranca_id, gerado_em);
CREATE INDEX ix_pagamentos_online_aluno ON pagamentos_online (aluno_id, gerado_em);
-- A consulta periódica e a lista do ADMIN: só os em aberto e os a devolver.
CREATE INDEX ix_pagamentos_online_aguardando ON pagamentos_online (valido_ate) WHERE situacao = 'AGUARDANDO';
CREATE INDEX ix_pagamentos_online_a_devolver ON pagamentos_online (recebido_em) WHERE situacao = 'A_DEVOLVER';

ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA', 'CAIXA',
                        'MATRICULA', 'PAGAMENTO_ONLINE'));
