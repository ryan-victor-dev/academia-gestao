-- E-mail confirmado (ADR 0038): aluno e equipe confirmam o e-mail no primeiro acesso, com um
-- código de 6 dígitos; o "esqueci a senha" usa um código do mesmo jeito. Nulo: não confirmado.
ALTER TABLE usuarios ADD COLUMN email_confirmado_em TIMESTAMPTZ;

-- Cada código enviado. Só o hash (BCrypt): quem lê o banco (ou um backup) não usa o código. Vale
-- até expira_em, uma vez só e com poucas tentativas; o novo encerra o anterior.
CREATE TABLE codigos_de_email (
    id          UUID         PRIMARY KEY,
    usuario_id  UUID         NOT NULL REFERENCES usuarios (id),
    finalidade  VARCHAR(12)  NOT NULL CHECK (finalidade IN ('CONFIRMACAO', 'RECUPERACAO')),
    -- Para onde foi: se o e-mail do login mudar depois, o código não serve mais.
    email       VARCHAR(150) NOT NULL,
    codigo_hash VARCHAR(100) NOT NULL,
    criado_em   TIMESTAMPTZ  NOT NULL,
    expira_em   TIMESTAMPTZ  NOT NULL CHECK (expira_em > criado_em),
    tentativas  INT          NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    -- Usado, trocado por um novo, ou tentativas esgotadas.
    encerrado_em TIMESTAMPTZ
);
CREATE INDEX ix_codigos_de_email_usuario ON codigos_de_email (usuario_id, finalidade, criado_em);
-- Um aberto por usuário e finalidade: dois pedidos ao mesmo tempo não deixam dois valendo.
CREATE UNIQUE INDEX uq_codigos_de_email_aberto ON codigos_de_email (usuario_id, finalidade)
    WHERE encerrado_em IS NULL;
