-- Anexos das publicações (ADR 0047, decisão 6): fotos até 5 MB, documentos (PDF) até 10 MB e vídeos curtos até
-- 50 MB, até 10 por publicação. Ficam no próprio banco, em pedaços de até 1 MB: entram no backup cifrado que já
-- existe (ADR 0041) sem nada novo, saem junto com a publicação, e a aplicação (com disco só de leitura) nunca
-- guarda arquivo. O tipo vem do conteúdo (os primeiros bytes), nunca do nome nem do que o navegador declarou.
CREATE TABLE publicacao_anexos (
    id            UUID         PRIMARY KEY,
    publicacao_id UUID         NOT NULL REFERENCES publicacoes (id) ON DELETE CASCADE,
    tipo          VARCHAR(20)  NOT NULL CHECK (tipo IN ('IMAGEM', 'VIDEO', 'DOCUMENTO')),
    conteudo_tipo VARCHAR(30)  NOT NULL CHECK (conteudo_tipo IN ('image/jpeg', 'image/png', 'image/webp', 'image/gif',
                                                                 'video/mp4', 'video/webm', 'application/pdf')),
    nome          VARCHAR(200) NOT NULL CHECK (btrim(nome) <> ''),
    tamanho       BIGINT       NOT NULL CHECK (tamanho > 0 AND tamanho <= 52428800),
    -- ENVIANDO: os pedaços estão chegando (cada um numa transação curta, para um envio lento não segurar
    -- uma conexão do banco); só PRONTO aparece. O que ficou ENVIANDO (envio interrompido) é limpo depois.
    situacao      VARCHAR(10)  NOT NULL CHECK (situacao IN ('ENVIANDO', 'PRONTO')),
    criado_em     TIMESTAMPTZ  NOT NULL,
    criado_por    UUID         NOT NULL REFERENCES usuarios (id)
);
CREATE INDEX ix_publicacao_anexos_publicacao ON publicacao_anexos (publicacao_id, criado_em);
CREATE INDEX ix_publicacao_anexos_enviando ON publicacao_anexos (criado_em) WHERE situacao = 'ENVIANDO';

CREATE TABLE anexo_partes (
    anexo_id UUID    NOT NULL REFERENCES publicacao_anexos (id) ON DELETE CASCADE,
    ordem    INTEGER NOT NULL CHECK (ordem >= 0),
    dados    BYTEA   NOT NULL CHECK (octet_length(dados) BETWEEN 1 AND 1048576),
    PRIMARY KEY (anexo_id, ordem)
);
-- Vídeo e foto já vêm comprimidos: o banco não tenta comprimir de novo (só gastaria CPU).
ALTER TABLE anexo_partes ALTER COLUMN dados SET STORAGE EXTERNAL;
