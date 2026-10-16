-- Login criado com senha temporária (gerada pelo sistema) precisa trocar a senha
-- antes de usar qualquer outra parte da API.
ALTER TABLE usuarios ADD COLUMN precisa_trocar_senha BOOLEAN NOT NULL DEFAULT FALSE;
