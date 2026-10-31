-- Relatório de idades e gênero (ADR 0048): só o ADMIN abre, e cada abertura vai para o registro de acessos (ADR 0031)
-- numa linha só, com quem e de onde. Ele mostra a orientação sexual de todos, dado sensível (LGPD, art. 5º, II); uma
-- linha por aluno listado faria milhares a cada abertura, que é o que o registro evita nas listas.
--
-- Como a consulta a um aluno, o relatório tem quem (obrigatório) e não tem conta digitada; e, ao contrário dela,
-- não tem um aluno só.
ALTER TABLE acessos DROP CONSTRAINT acessos_tipo_check;
ALTER TABLE acessos ADD CONSTRAINT acessos_tipo_check CHECK (tipo IN ('CONSULTA_ALUNO', 'LOGIN', 'LOGIN_FALHO',
    'LOGIN_BLOQUEADO', 'TROCA_SENHA_FALHA', 'RELATORIO_PERFIL'));

ALTER TABLE acessos DROP CONSTRAINT ck_acessos_conta;
ALTER TABLE acessos ADD CONSTRAINT ck_acessos_conta CHECK ((tipo IN ('CONSULTA_ALUNO', 'RELATORIO_PERFIL')) = (conta IS NULL));

ALTER TABLE acessos DROP CONSTRAINT ck_acessos_quem;
ALTER TABLE acessos ADD CONSTRAINT ck_acessos_quem CHECK (tipo NOT IN ('CONSULTA_ALUNO', 'RELATORIO_PERFIL')
    OR usuario_id IS NOT NULL);
