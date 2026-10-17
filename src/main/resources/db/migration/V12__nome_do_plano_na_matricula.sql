-- A matrícula passa a guardar também o NOME do plano no dia em que foi feita, como já
-- guarda o valor e a duração: é o retrato do que foi contratado. Sem isso, o aluno de um
-- plano depois renomeado ou inativado (que ele não pode mais consultar) não saberia o
-- nome do próprio plano.
ALTER TABLE matriculas ADD COLUMN plano_nome VARCHAR(80);

-- Matrículas anteriores a esta migration ficam com o nome atual do plano: o nome do dia
-- da matrícula não foi guardado, e este é o melhor dado que existe.
UPDATE matriculas m SET plano_nome = p.nome FROM planos p WHERE p.id = m.plano_id;

ALTER TABLE matriculas
    ALTER COLUMN plano_nome SET NOT NULL,
    ADD CONSTRAINT ck_matriculas_plano_nome CHECK (btrim(plano_nome) <> '');
