-- Primeiro acesso do aluno (ADR 0039): o aluno sem login pede o código pelo CPF e o e-mail do
-- cadastro. O login ainda não existe, então esse código é do aluno; os outros, do login.
ALTER TABLE codigos_de_email ALTER COLUMN usuario_id DROP NOT NULL;
ALTER TABLE codigos_de_email ADD COLUMN aluno_id UUID REFERENCES alunos (id);
ALTER TABLE codigos_de_email ALTER COLUMN finalidade TYPE VARCHAR(20);
ALTER TABLE codigos_de_email DROP CONSTRAINT codigos_de_email_finalidade_check;
ALTER TABLE codigos_de_email ADD CONSTRAINT codigos_de_email_finalidade_check
    CHECK (finalidade IN ('CONFIRMACAO', 'RECUPERACAO', 'PRIMEIRO_ACESSO'));
ALTER TABLE codigos_de_email ADD CONSTRAINT codigos_de_email_dono_check
    CHECK (CASE WHEN finalidade = 'PRIMEIRO_ACESSO' THEN aluno_id IS NOT NULL AND usuario_id IS NULL
                ELSE usuario_id IS NOT NULL AND aluno_id IS NULL END);
CREATE INDEX ix_codigos_de_email_aluno ON codigos_de_email (aluno_id, finalidade, criado_em);
-- Um aberto por aluno, como por login (V50).
CREATE UNIQUE INDEX uq_codigos_de_email_aberto_aluno ON codigos_de_email (aluno_id, finalidade)
    WHERE encerrado_em IS NULL;
