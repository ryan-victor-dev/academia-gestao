-- Sessões renováveis (ADR 0033): o login abre uma sessão, e o cookie dela troca um token de
-- acesso vencido por um novo até o fim do prazo (12 h para a equipe, 30 dias para o aluno).
-- Só o hash SHA-256 do segredo fica aqui: quem lê o banco não consegue usar a sessão.
CREATE TABLE sessoes (
    id                      UUID        PRIMARY KEY,
    -- A aplicação não apaga usuários (só testes e scripts de validação): a sessão vai junto.
    usuario_id              UUID        NOT NULL REFERENCES usuarios (id) ON DELETE CASCADE,
    segredo_hash            BYTEA       NOT NULL CHECK (octet_length(segredo_hash) = 32),
    -- O segredo de antes da última troca: aceito por 30 s (duas abas renovando juntas).
    segredo_anterior_hash   BYTEA       CHECK (octet_length(segredo_anterior_hash) = 32),
    -- Versão dos tokens do usuário na abertura: se mudar (senha, desativação), não renova mais.
    versao_token            INT         NOT NULL,
    criada_em               TIMESTAMPTZ NOT NULL,
    renovada_em             TIMESTAMPTZ NOT NULL,
    expira_em               TIMESTAMPTZ NOT NULL,
    encerrada_em            TIMESTAMPTZ,
    CHECK (expira_em > criada_em)
);

CREATE INDEX ix_sessoes_usuario ON sessoes (usuario_id);
-- A limpeza agendada procura as vencidas.
CREATE INDEX ix_sessoes_expira_em ON sessoes (expira_em);
