-- CPF da equipe (ADR 0047, decisão 1). Obrigatório em todo login novo da equipe, mas quem já existia
-- fica sem até o ADMIN preencher: por isso a coluna aceita nulo, e a obrigação fica na aplicação. Um
-- CHECK, mesmo NOT VALID, valeria também no UPDATE, e renomear um funcionário antigo passaria a falhar.
--
-- O CPF vem limpo da aplicação (só os 11 dígitos, já conferidos). O login de aluno nunca tem: o CPF
-- dele fica no cadastro do aluno.
ALTER TABLE usuarios ADD COLUMN cpf VARCHAR(11);
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_cpf CHECK (cpf IS NULL OR cpf ~ '^[0-9]{11}$');
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_cpf_so_da_equipe CHECK (perfil <> 'ALUNO' OR cpf IS NULL);

-- O mesmo CPF não aparece em dois logins da equipe. A checagem da aplicação dá a mensagem clara; numa
-- corrida, quem barra é este índice.
CREATE UNIQUE INDEX uq_usuarios_cpf ON usuarios (cpf);
