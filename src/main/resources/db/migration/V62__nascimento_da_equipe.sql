-- Data de nascimento da equipe (ADR 0048). Obrigatória em todo login novo da equipe, mas quem já existia fica
-- sem até alguém preencher: por isso a coluna aceita nulo, e a obrigação fica na aplicação, como o CPF (V55).
-- Só o ADMIN e a própria pessoa veem (a lista da equipe já é só do ADMIN).
--
-- O login de aluno nunca tem: a data do aluno fica no cadastro dele (alunos.data_nascimento). Os limites (nem
-- no futuro, nem mais de 120 anos atrás) dependem de "hoje" e ficam na aplicação; o banco só barra o absurdo.
ALTER TABLE usuarios ADD COLUMN data_nascimento DATE;
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_nascimento_so_da_equipe CHECK (perfil <> 'ALUNO' OR data_nascimento IS NULL);
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_nascimento_valido CHECK (data_nascimento IS NULL OR data_nascimento >= DATE '1900-01-01');
