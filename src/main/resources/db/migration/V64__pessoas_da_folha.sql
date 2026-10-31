-- Pagamentos à equipe (ADR 0049): quem recebe, com o vínculo (CLT, MEI ou autônomo) e o valor combinado. Só o
-- ADMIN vê. Quem tem login da equipe aparece na folha pela tabela usuarios, e ganha uma linha aqui quando o ADMIN
-- define o vínculo; quem trabalha na academia sem usar o sistema (limpeza, manutenção, aula coletiva) existe só
-- aqui, com nome e CPF, e nunca tem acesso.
CREATE TABLE pessoas_da_folha (
    id              UUID          PRIMARY KEY,
    usuario_id      UUID          UNIQUE REFERENCES usuarios (id),
    -- Só de quem não tem login: o nome e o CPF de quem tem ficam no login (e mudam na aba Equipe).
    nome            VARCHAR(150)  CHECK (nome IS NULL OR btrim(nome) <> ''),
    cpf             VARCHAR(11)   CHECK (cpf IS NULL OR cpf ~ '^[0-9]{11}$'),
    vinculo         VARCHAR(10)   NOT NULL CHECK (vinculo IN ('CLT', 'MEI', 'AUTONOMO')),
    -- Nulo: sem valor fixo (quem recebe por aula, por exemplo). O pago é sempre digitado, nunca calculado daqui.
    valor_combinado NUMERIC(10, 2) CHECK (valor_combinado IS NULL OR valor_combinado > 0),
    -- Só de quem não tem login: a situação de quem tem é a do login.
    ativo           BOOLEAN       NOT NULL DEFAULT TRUE,
    versao          BIGINT        NOT NULL DEFAULT 0,
    criado_em       TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_pessoas_da_folha_quem CHECK (
        (usuario_id IS NOT NULL AND nome IS NULL AND cpf IS NULL AND ativo)
        OR (usuario_id IS NULL AND nome IS NOT NULL AND cpf IS NOT NULL))
);

-- O mesmo CPF não aparece em duas pessoas sem login: quem barra é este índice (a aplicação transforma o erro em 409,
-- também na corrida). O CPF de quem tem login a aplicação confere à parte: essa pessoa entra pelo login, e não de novo.
CREATE UNIQUE INDEX uq_pessoas_da_folha_cpf ON pessoas_da_folha (cpf);

ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO',
    'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA', 'CAIXA', 'MATRICULA', 'PAGAMENTO_ONLINE', 'PUBLICACAO',
    'PESSOA_DA_FOLHA'));

-- Cada consulta à folha vai para o registro de acessos (ADR 0031), numa linha só, com quem e de onde: ela mostra o
-- valor combinado com cada pessoa (o salário). Como o relatório de idades (V63): tem quem, sem conta digitada nem aluno.
ALTER TABLE acessos DROP CONSTRAINT acessos_tipo_check;
ALTER TABLE acessos ADD CONSTRAINT acessos_tipo_check CHECK (tipo IN ('CONSULTA_ALUNO', 'LOGIN', 'LOGIN_FALHO',
    'LOGIN_BLOQUEADO', 'TROCA_SENHA_FALHA', 'RELATORIO_PERFIL', 'CONSULTA_FOLHA'));

ALTER TABLE acessos DROP CONSTRAINT ck_acessos_conta;
ALTER TABLE acessos ADD CONSTRAINT ck_acessos_conta CHECK ((tipo IN ('CONSULTA_ALUNO', 'RELATORIO_PERFIL', 'CONSULTA_FOLHA'))
    = (conta IS NULL));

ALTER TABLE acessos DROP CONSTRAINT ck_acessos_quem;
ALTER TABLE acessos ADD CONSTRAINT ck_acessos_quem CHECK (tipo NOT IN ('CONSULTA_ALUNO', 'RELATORIO_PERFIL', 'CONSULTA_FOLHA')
    OR usuario_id IS NOT NULL);
