-- Número da versão do cadastro, incrementado a cada alteração. É o que vai no ETag e
-- permite recusar uma edição feita sobre dados que outra pessoa já mudou.
ALTER TABLE alunos ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
