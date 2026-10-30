-- Notícias, comunicados e eventos da academia (ADR 0047, decisão 6). O ADMIN e a recepção publicam; todos que
-- têm login leem, e é a página inicial. Texto simples (a tela nunca interpreta HTML) e links só http(s).
-- Os anexos (fotos, vídeos e documentos) vêm na V58.
CREATE TABLE publicacoes (
    id            UUID          PRIMARY KEY,
    tipo          VARCHAR(20)   NOT NULL CHECK (tipo IN ('NOTICIA', 'COMUNICADO', 'EVENTO')),
    titulo        VARCHAR(150)  NOT NULL CHECK (btrim(titulo) <> '' AND titulo = btrim(titulo)),
    texto         VARCHAR(5000) NOT NULL CHECK (btrim(texto) <> ''),
    -- Só o evento tem quando e onde; o início é obrigatório nele, e o fim, se houver, não vem antes.
    evento_inicio TIMESTAMPTZ,
    evento_fim    TIMESTAMPTZ,
    evento_local  VARCHAR(150)  CHECK (btrim(evento_local) <> ''),
    criada_em     TIMESTAMPTZ   NOT NULL,
    criada_por    UUID          NOT NULL REFERENCES usuarios (id),
    editada_em    TIMESTAMPTZ,
    editada_por   UUID          REFERENCES usuarios (id),
    CONSTRAINT ck_publicacoes_evento CHECK ((tipo = 'EVENTO') = (evento_inicio IS NOT NULL)),
    CONSTRAINT ck_publicacoes_so_evento_tem_fim_e_local CHECK (tipo = 'EVENTO'
        OR (evento_fim IS NULL AND evento_local IS NULL)),
    CONSTRAINT ck_publicacoes_fim_depois_do_inicio CHECK (evento_fim IS NULL OR evento_fim >= evento_inicio),
    CONSTRAINT ck_publicacoes_edicao CHECK ((editada_em IS NULL) = (editada_por IS NULL))
);
-- A página inicial lista da mais nova para a mais antiga; os próximos eventos, pelo início.
CREATE INDEX ix_publicacoes_criada_em ON publicacoes (criada_em DESC, id);
CREATE INDEX ix_publicacoes_eventos ON publicacoes (evento_inicio) WHERE tipo = 'EVENTO';

-- Os links da publicação, na ordem em que foram dados (até 10). Saem com ela.
CREATE TABLE publicacao_links (
    publicacao_id UUID         NOT NULL REFERENCES publicacoes (id) ON DELETE CASCADE,
    ordem         SMALLINT     NOT NULL CHECK (ordem BETWEEN 0 AND 9),
    url           VARCHAR(500) NOT NULL CHECK (url ~ '^https?://[^[:space:]]+$'),
    rotulo        VARCHAR(100) CHECK (btrim(rotulo) <> ''),
    PRIMARY KEY (publicacao_id, ordem)
);

-- A trilha passa a aceitar a publicação.
ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO',
    'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA', 'CAIXA', 'MATRICULA', 'PAGAMENTO_ONLINE', 'PUBLICACAO'));
