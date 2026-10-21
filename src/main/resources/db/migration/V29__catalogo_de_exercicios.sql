-- Catálogo de exercícios da academia (etapa 7, ADR 0027): os instrutores mantêm, e a ficha
-- de treino escolhe dele. Não se apaga: o inativo sai das fichas novas e fica nas antigas.
CREATE TABLE exercicios (
    id             UUID         PRIMARY KEY,
    nome           VARCHAR(80)  NOT NULL CHECK (btrim(nome) <> ''),
    grupo_muscular VARCHAR(20)  NOT NULL CHECK (grupo_muscular IN (
        'PEITO', 'COSTAS', 'OMBROS', 'BICEPS', 'TRICEPS', 'ANTEBRACO', 'ABDOMEN', 'QUADRICEPS', 'POSTERIORES',
        'GLUTEOS', 'PANTURRILHA', 'CORPO_INTEIRO', 'CARDIO')),
    observacao     VARCHAR(500) CHECK (btrim(observacao) <> ''),
    ativo          BOOLEAN      NOT NULL DEFAULT true,
    criado_em      TIMESTAMPTZ  NOT NULL,
    -- Versão do cadastro para o ETag/If-Match (ADR 0009).
    versao         BIGINT       NOT NULL DEFAULT 0
);

-- Dois exercícios ATIVOS não podem ter o mesmo nome ("Supino", "SUPINO" e "Supíno" são o
-- mesmo), como nos planos (V10): o mesmo exercício não aparece com nomes diferentes.
CREATE UNIQUE INDEX uk_exercicios_nome_ativo ON exercicios (nome_normalizado(nome)) WHERE ativo;

ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO', 'EXERCICIO'));
