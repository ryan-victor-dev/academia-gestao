-- Aluno que sai da academia é inativado, não apagado: o histórico de matrículas
-- e mensalidades continua apontando para ele.
ALTER TABLE alunos ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT TRUE;

-- O CPF é gravado só com os 11 dígitos, sem pontos e traço.
-- Os dígitos verificadores são conferidos no Java (classe Cpf).
ALTER TABLE alunos ADD CONSTRAINT ck_alunos_cpf_formato CHECK (cpf ~ '^[0-9]{11}$');
