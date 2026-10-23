-- Registro de acessos (ADR 0031): quem da equipe abriu o cadastro de qual aluno, e cada
-- login (entrada, senha errada, bloqueio pelo limite) e senha atual errada na troca, com a
-- conta e o IP. Separado da trilha de alterações: são muitos mais registros, e ficam só 1
-- ano (dado pessoal: quem consultou e o IP; a LGPD pede guardar o mínimo). A aplicação só
-- insere e lê (UsuarioDaAplicacaoNoBanco); o vencido sai pela função abaixo.
CREATE TABLE acessos (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    momento    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tipo       VARCHAR(20)  NOT NULL CHECK (tipo IN ('CONSULTA_ALUNO', 'LOGIN', 'LOGIN_FALHO', 'LOGIN_BLOQUEADO',
                                                      'TROCA_SENHA_FALHA')),
    -- Quem: o usuário logado (consulta, troca de senha) ou o dono da conta do login, se ela
    -- existe. Sem chave estrangeira, como a trilha: o registro não impede apagar um usuário.
    usuario_id UUID,
    -- O e-mail do login, como foi digitado (normalizado), mesmo de conta que não existe.
    conta      VARCHAR(150),
    ip         VARCHAR(45)  NOT NULL,
    aluno_id   UUID,

    -- A consulta tem quem e qual aluno; o login e a troca têm a conta, e nunca um aluno.
    CONSTRAINT ck_acessos_consulta CHECK ((tipo = 'CONSULTA_ALUNO') = (aluno_id IS NOT NULL)),
    CONSTRAINT ck_acessos_quem CHECK (tipo <> 'CONSULTA_ALUNO' OR usuario_id IS NOT NULL),
    CONSTRAINT ck_acessos_conta CHECK ((tipo = 'CONSULTA_ALUNO') = (conta IS NULL)),
    CONSTRAINT ck_acessos_troca CHECK (tipo <> 'TROCA_SENHA_FALHA' OR usuario_id IS NOT NULL)
);
CREATE INDEX ix_acessos_usuario ON acessos (usuario_id, id DESC);
CREATE INDEX ix_acessos_aluno ON acessos (aluno_id, id DESC) WHERE aluno_id IS NOT NULL;
CREATE INDEX ix_acessos_conta ON acessos (conta, id DESC) WHERE conta IS NOT NULL;

-- Como a apagar_auditoria_vencida (V21), com o prazo de 1 ano.
CREATE FUNCTION apagar_acessos_vencidos(lote integer) RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    WITH vencidos AS (
        SELECT id FROM public.acessos
        WHERE momento < now() - interval '1 year'
        ORDER BY id
        LIMIT least(greatest(lote, 1), 10000)
    ), apagados AS (
        DELETE FROM public.acessos a USING vencidos v WHERE a.id = v.id RETURNING 1
    )
    SELECT count(*)::integer FROM apagados
$$;
REVOKE ALL ON FUNCTION apagar_acessos_vencidos(integer) FROM PUBLIC;
