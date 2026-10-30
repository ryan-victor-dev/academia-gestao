-- SMS (ADR 0047, decisão 9). O simulado (só desenvolvimento e testes) guarda aqui o que mandaria, para quem testa
-- conferir; com a empresa de verdade, a tabela fica vazia. O aviso de fatura por SMS usa a mesma reserva do e-mail
-- (avisos_de_fatura, canal SMS, V59).
CREATE TABLE sms_simulados (
    id         UUID          PRIMARY KEY,
    celular    VARCHAR(11)   NOT NULL CHECK (celular ~ '^[1-9]{2}9[0-9]{8}$'),
    texto      VARCHAR(1000) NOT NULL,
    enviado_em TIMESTAMPTZ   NOT NULL
);
