-- Busca de alunos por nome (diagnóstico de 18/10, E2; pendências baixas, item 21). A página é
-- lida pelo índice de nome e id, na ordem, e o filtro calculava nome_normalizado (unaccent) em
-- cada linha descartada: com um nome comum, 66 mil cálculos para achar 20 (131 ms em 100 mil
-- alunos). Gravado na linha, o nome normalizado só é comparado (20 ms).
--
-- Coluna gerada: o banco a mantém sozinho a cada INSERT e UPDATE do nome, e ninguém a grava à
-- mão. O índice de trigramas passa para ela (a contagem da página o usa), e o antigo, sobre a
-- expressão, sai.
ALTER TABLE alunos ADD COLUMN nome_busca TEXT GENERATED ALWAYS AS (nome_normalizado(nome)) STORED;
CREATE INDEX ix_alunos_nome_busca ON alunos USING gin (nome_busca gin_trgm_ops);
DROP INDEX ix_alunos_busca_nome;
