CREATE TABLE planos (
    id            UUID          PRIMARY KEY,
    nome          VARCHAR(80)   NOT NULL,
    valor_mensal  NUMERIC(10,2) NOT NULL CHECK (valor_mensal > 0),
    duracao_meses INT           NOT NULL CHECK (duracao_meses > 0),
    ativo         BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE alunos (
    id              UUID         PRIMARY KEY,
    nome            VARCHAR(150) NOT NULL,
    cpf             CHAR(11)     NOT NULL UNIQUE,
    email           VARCHAR(150),
    telefone        VARCHAR(20),
    data_nascimento DATE,
    criado_em       TIMESTAMPTZ  NOT NULL DEFAULT now()
);