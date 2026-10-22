-- Aluno inativo com login só de leitura (decisão do dono, D1 do diagnóstico da etapa 7,
-- ADR 0029). Antes, inativar o aluno desligava o login dele, e o direito de ver os próprios
-- dados (ADRs 0008 e 0027) não se cumpria. Agora o login continua ativo, mas só consulta.
ALTER TABLE usuarios ADD COLUMN somente_leitura BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_somente_leitura_so_aluno
    CHECK (NOT somente_leitura OR perfil = 'ALUNO');

-- Os logins já desligados pela inativação do aluno voltam, só de leitura. Tokens antigos
-- não valem (versão nova): o próximo login já sai restrito.
UPDATE usuarios u SET ativo = true, somente_leitura = true, versao_token = u.versao_token + 1
FROM alunos a
WHERE a.id = u.aluno_id AND u.perfil = 'ALUNO' AND NOT u.ativo AND NOT a.ativo;
