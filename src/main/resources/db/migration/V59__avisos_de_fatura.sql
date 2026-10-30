-- Avisos de fatura (ADR 0047, decisão 7): 5, 3 e 1 dia antes do vencimento, no dia, e com 1, 3 e 5 dias de atraso.
-- Uma linha por fatura, marco e canal: a chave primária garante que o mesmo aviso nunca sai duas vezes, mesmo com o
-- servidor reiniciando no meio. A linha é gravada (RESERVADO) na mesma transação que confere que a fatura continua em
-- aberto, antes de enviar; depois do envio, vira ENVIADO, ou FALHOU (o envio é tentado de novo, até 3 vezes no dia).
CREATE TABLE avisos_de_fatura (
    cobranca_id   UUID        NOT NULL REFERENCES cobrancas (id),
    marco         VARCHAR(20) NOT NULL CHECK (marco IN ('VENCE_EM_5', 'VENCE_EM_3', 'VENCE_EM_1', 'VENCE_HOJE',
                                                        'ATRASADA_1', 'ATRASADA_3', 'ATRASADA_5')),
    canal         VARCHAR(10) NOT NULL CHECK (canal IN ('EMAIL', 'SMS')),
    situacao      VARCHAR(10) NOT NULL CHECK (situacao IN ('RESERVADO', 'ENVIADO', 'FALHOU')),
    tentativas    SMALLINT    NOT NULL DEFAULT 1 CHECK (tentativas BETWEEN 1 AND 3),
    reservado_em  TIMESTAMPTZ NOT NULL,
    enviado_em    TIMESTAMPTZ,
    PRIMARY KEY (cobranca_id, marco, canal),
    CONSTRAINT ck_avisos_de_fatura_enviado CHECK ((situacao = 'ENVIADO') = (enviado_em IS NOT NULL))
);
