-- Retenção da trilha de auditoria (diagnóstico de 2026-10-18, decisão do dono): 5 anos.
-- A trilha guarda valores antigos de dados pessoais (LGPD) e crescia sem limite (277 MB por
-- milhão de registros). 5 anos cobrem o prazo do Código de Defesa do Consumidor para
-- reclamar de uma cobrança e os prazos fiscais comuns.
--
-- O usuário da aplicação continua sem DELETE na trilha (V18, UsuarioDaAplicacaoNoBanco):
-- ele só pode chamar esta função, que roda com os direitos do dono e apaga somente o que
-- já passou do prazo. Nem uma injeção de SQL apaga o histórico recente. O prazo fica aqui,
-- e não numa configuração da aplicação, pelo mesmo motivo: mudar é uma migração nova.
--
-- Em lotes (até 10 mil por chamada), para nenhuma transação ficar longa nem passar do
-- statement_timeout de 5 s. O id cresce com o momento, então os vencidos são os primeiros.
CREATE FUNCTION apagar_auditoria_vencida(lote integer) RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    -- Função com os direitos do dono: o search_path fixo impede que alguém faça ela usar
    -- uma tabela "auditoria" de outro esquema.
    SET search_path = pg_catalog, public
AS $$
    WITH vencidos AS (
        SELECT id FROM public.auditoria
        WHERE momento < now() - interval '5 years'
        ORDER BY id
        LIMIT least(greatest(lote, 1), 10000)
    ), apagados AS (
        DELETE FROM public.auditoria a USING vencidos v WHERE a.id = v.id RETURNING 1
    )
    SELECT count(*)::integer FROM apagados
$$;

-- Toda função nasce executável por qualquer usuário (PUBLIC); esta, só pela aplicação
-- (UsuarioDaAplicacaoNoBanco dá o EXECUTE a cada subida).
REVOKE ALL ON FUNCTION apagar_auditoria_vencida(integer) FROM PUBLIC;
