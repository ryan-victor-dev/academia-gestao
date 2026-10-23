-- Diagnóstico da etapa 8 (M3): um ataque de login gravava uma linha em "acessos" por tentativa
-- bloqueada (~300/s de uma máquina só, guardadas 1 ano), e a retenção varria a tabela inteira
-- todo dia, mesmo sem nada vencido (0,5 s com 3 milhões de linhas; perto de 30 milhões passaria
-- dos 5 s do statement_timeout da aplicação, e nada mais seria apagado). O bloqueio passa a ser
-- gravado uma vez por conta e IP na janela do limite (no Java); aqui, a retenção.
--
-- BRIN em "momento": as duas tabelas só crescem em ordem de tempo, e o índice ocupa poucos KB
-- (24 kB com 3 milhões de linhas). As funções passam a ordenar por "momento" (a mesma ordem do
-- id), e não pelo id: ordenando pelo id, o plano genérico da função percorria a chave primária
-- inteira atrás de vencidos. Medido num Postgres 17 com 3 milhões de linhas: 460 ms → menos de
-- 1 ms sem nada vencido; lotes de 10 mil vencidos em menos de 30 ms.
CREATE INDEX ix_acessos_momento ON acessos USING brin (momento);
CREATE INDEX ix_auditoria_momento ON auditoria USING brin (momento);

-- CREATE OR REPLACE mantém o dono e as permissões (só a aplicação executa, V21 e V38).
CREATE OR REPLACE FUNCTION apagar_acessos_vencidos(lote integer) RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    WITH vencidos AS (
        SELECT id FROM public.acessos
        WHERE momento < now() - interval '1 year'
        ORDER BY momento
        LIMIT least(greatest(lote, 1), 10000)
    ), apagados AS (
        DELETE FROM public.acessos a USING vencidos v WHERE a.id = v.id RETURNING 1
    )
    SELECT count(*)::integer FROM apagados
$$;

CREATE OR REPLACE FUNCTION apagar_auditoria_vencida(lote integer) RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    WITH vencidos AS (
        SELECT id FROM public.auditoria
        WHERE momento < now() - interval '5 years'
        ORDER BY momento
        LIMIT least(greatest(lote, 1), 10000)
    ), apagados AS (
        DELETE FROM public.auditoria a USING vencidos v WHERE a.id = v.id RETURNING 1
    )
    SELECT count(*)::integer FROM apagados
$$;

-- A procura do último bloqueio da conta e do IP, a cada tentativa bloqueada.
CREATE INDEX ix_acessos_bloqueios ON acessos (conta, ip, momento DESC) WHERE tipo = 'LOGIN_BLOQUEADO';
