-- Trilha de auditoria (revisão de 2026-10-17): mudar o preço ou as regras de um plano,
-- editar ou inativar alunos e mexer na equipe não deixava rastro de quem fez nem do valor
-- anterior. Cada alteração grava uma linha aqui, na mesma transação da alteração.
CREATE TABLE auditoria (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    momento    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Quem fez: o id do usuário logado (o "sub" do token). Vazio = o próprio sistema, como
    -- o primeiro ADMIN e a recuperação de ADMIN pela linha de comando. Sem chave estrangeira:
    -- a trilha não pode impedir apagar um usuário, nem sumir junto com ele.
    autor      VARCHAR(64),
    entidade   VARCHAR(20)  NOT NULL CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO')),
    entidade_id UUID        NOT NULL,
    acao       VARCHAR(30)  NOT NULL,
    -- Só o que mudou: {"campo": {"antes": ..., "depois": ...}}. Nunca senhas.
    alteracoes JSONB        NOT NULL
);

-- O histórico de um plano, aluno ou usuário, do mais novo para o mais antigo.
CREATE INDEX ix_auditoria_entidade ON auditoria (entidade, entidade_id, id DESC);
