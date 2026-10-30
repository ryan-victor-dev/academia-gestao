-- Avisos da academia (ADR 0047, decisão 8): um evento ou comunicado publicado com "avisar" vai por e-mail e SMS a alunos
-- e equipe, e a pessoa pode desligar.
--
-- O que foi publicado fica guardado como estava (o aviso sai com o texto daquele momento), e a fila tem uma linha por
-- pessoa e canal. Uma tarefa manda aos poucos. O contato (e-mail, celular) não é copiado: é lido do cadastro na hora do
-- envio, e quem desligou ou deixou de ser ativo nesse meio tempo não recebe.
CREATE TABLE avisos_da_academia (
    publicacao_id UUID          PRIMARY KEY,
    tipo          VARCHAR(20)   NOT NULL CHECK (tipo IN ('COMUNICADO', 'EVENTO')),
    titulo        VARCHAR(150)  NOT NULL,
    texto         VARCHAR(5000) NOT NULL,
    evento_inicio TIMESTAMPTZ,
    evento_fim    TIMESTAMPTZ,
    evento_local  VARCHAR(150),
    criado_em     TIMESTAMPTZ   NOT NULL
);

CREATE TABLE envios_da_academia (
    publicacao_id UUID        NOT NULL REFERENCES avisos_da_academia (publicacao_id),
    aluno_id      UUID        REFERENCES alunos (id) ON DELETE CASCADE,
    usuario_id    UUID        REFERENCES usuarios (id) ON DELETE CASCADE,
    canal         VARCHAR(10) NOT NULL CHECK (canal IN ('EMAIL', 'SMS')),
    situacao      VARCHAR(10) NOT NULL CHECK (situacao IN ('PENDENTE', 'ENVIADO', 'FALHOU', 'CANCELADO')),
    tentativas    SMALLINT    NOT NULL DEFAULT 0 CHECK (tentativas BETWEEN 0 AND 3),
    criado_em     TIMESTAMPTZ NOT NULL,
    enviado_em    TIMESTAMPTZ,
    CONSTRAINT ck_envios_da_academia_um_destino CHECK ((aluno_id IS NULL) <> (usuario_id IS NULL)),
    CONSTRAINT ck_envios_da_academia_enviado CHECK ((situacao = 'ENVIADO') = (enviado_em IS NOT NULL)),
    -- A equipe não tem celular no sistema.
    CONSTRAINT ck_envios_da_academia_sms_so_aluno CHECK (canal = 'EMAIL' OR aluno_id IS NOT NULL)
);
-- A mesma pessoa não recebe o mesmo aviso duas vezes pelo mesmo canal.
CREATE UNIQUE INDEX uq_envios_da_academia_aluno ON envios_da_academia (publicacao_id, aluno_id, canal) WHERE aluno_id IS NOT NULL;
CREATE UNIQUE INDEX uq_envios_da_academia_usuario ON envios_da_academia (publicacao_id, usuario_id, canal) WHERE usuario_id IS NOT NULL;
-- A tarefa busca os que faltam, dos mais antigos aos mais novos.
CREATE INDEX ix_envios_da_academia_a_enviar ON envios_da_academia (criado_em) WHERE situacao IN ('PENDENTE', 'FALHOU');

-- Quem desligou os avisos da academia (o de fatura não se desliga). Sem linha, recebe.
CREATE TABLE preferencias_de_avisos (
    aluno_id        UUID        REFERENCES alunos (id) ON DELETE CASCADE,
    usuario_id      UUID        REFERENCES usuarios (id) ON DELETE CASCADE,
    recebe_da_academia BOOLEAN  NOT NULL,
    atualizado_em   TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_preferencias_de_avisos_um_dono CHECK ((aluno_id IS NULL) <> (usuario_id IS NULL))
);
CREATE UNIQUE INDEX uq_preferencias_de_avisos_aluno ON preferencias_de_avisos (aluno_id) WHERE aluno_id IS NOT NULL;
CREATE UNIQUE INDEX uq_preferencias_de_avisos_usuario ON preferencias_de_avisos (usuario_id) WHERE usuario_id IS NOT NULL;
