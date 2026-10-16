-- Versão dos tokens do usuário. Vai no JWT e é conferida a cada requisição:
-- desativar, reativar ou trocar a senha aumenta o número, e todo token antigo
-- deixa de valer na hora.
ALTER TABLE usuarios ADD COLUMN versao_token INT NOT NULL DEFAULT 0;
