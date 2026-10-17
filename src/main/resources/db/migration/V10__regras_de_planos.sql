-- Nome "normalizado" para comparar planos: sem acento e sem diferenciar maiúsculas.
-- O unaccent não pode ir direto num índice (não é IMMUTABLE); esta função o envolve
-- fixando o dicionário, que é o jeito recomendado pelo próprio PostgreSQL.
CREATE FUNCTION nome_normalizado(texto TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT lower(public.unaccent('public.unaccent'::regdictionary, texto)) $$;

-- Dois planos ATIVOS não podem ter o mesmo nome ("Mensal", "MENSAL" e "Mênsal" são o
-- mesmo), para a recepção não escolher o errado. Um inativo antigo pode repetir o nome.
CREATE UNIQUE INDEX uk_planos_nome_ativo ON planos (nome_normalizado(nome)) WHERE ativo;

ALTER TABLE planos
    ADD COLUMN criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Versão do cadastro para o ETag/If-Match, como em alunos (ADR 0009).
    ADD COLUMN versao    BIGINT      NOT NULL DEFAULT 0,
    -- Até 5 anos: acima disso, quase certamente é erro de digitação.
    ADD CONSTRAINT ck_planos_duracao_maxima CHECK (duracao_meses <= 60);
