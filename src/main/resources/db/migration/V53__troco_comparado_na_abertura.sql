-- O troco da abertura comparado com o que ficou na gaveta no fechamento anterior (ADR 0046,
-- item 8). Declarar um troco menor do que ficou escondia uma sobra.

-- O dinheiro tirado da gaveta no fechamento (levado ao banco, por exemplo). O que fica na
-- gaveta para o dia seguinte é contado − retirado. Os fechamentos de antes ficam com zero.
ALTER TABLE fechamentos_de_caixa ADD COLUMN retirado NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE fechamentos_de_caixa ALTER COLUMN retirado DROP DEFAULT;
ALTER TABLE fechamentos_de_caixa ADD CONSTRAINT fechamentos_de_caixa_retirado_check
    CHECK (retirado >= 0 AND retirado <= contado);

-- O que ficou na gaveta no último fechamento do caixa anterior, gravado na abertura. Nulo: o
-- primeiro caixa, ou um aberto antes desta regra (sem nada com que comparar).
ALTER TABLE caixas ADD COLUMN troco_esperado NUMERIC(12,2) CHECK (troco_esperado >= 0);

-- Do caixa continua mudando só a situação: o troco esperado também fica.
CREATE OR REPLACE FUNCTION recusar_mudanca_na_abertura_do_caixa() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.dia <> OLD.dia OR NEW.troco <> OLD.troco OR NEW.aberto_em <> OLD.aberto_em
            OR NEW.aberto_por <> OLD.aberto_por OR NEW.troco_esperado IS DISTINCT FROM OLD.troco_esperado THEN
        RAISE EXCEPTION 'do caixa só muda a situação';
    END IF;
    RETURN NEW;
END $$;
