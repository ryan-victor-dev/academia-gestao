-- Busca e listagem de alunos (revisão de 2026-10-17): sem índice, cada busca varria a
-- tabela inteira e ordenava tudo para devolver uma página. Com 50 mil alunos, ~100 buscas
-- por segundo no máximo, e 1 s de espera com 64 usuários ao mesmo tempo.

-- Trigramas (pedaços de 3 letras) deixam um índice achar "%jose%" em qualquer parte do
-- nome. Vem junto com o PostgreSQL (contrib), como o unaccent.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- A busca compara nome_normalizado (V10: sem acento, minúsculo) dos dois lados, e o índice
-- precisa ser sobre a mesma expressão. Termos com menos de 3 letras não usam o índice.
CREATE INDEX ix_alunos_busca_nome ON alunos USING gin (nome_normalizado(nome) gin_trgm_ops);

-- A listagem é ordenada por nome e id: com este índice, a primeira página lê só as
-- primeiras linhas em ordem, em vez de ordenar a tabela inteira.
CREATE INDEX ix_alunos_nome_id ON alunos (nome, id);

-- Busca pelo começo do CPF (cpf LIKE '529%'): com a collation do banco (en_US), o índice
-- único do CPF não serve para LIKE; o pattern_ops compara byte a byte e serve.
CREATE INDEX ix_alunos_cpf_prefixo ON alunos (cpf bpchar_pattern_ops);
