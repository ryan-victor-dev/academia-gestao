-- Quanto devolver de um pagamento online (ADR 0035). Até aqui, a devolver era sempre tudo o que
-- chegou. Agora, o aluno que paga a mais (decisão do dono) fica com a cobrança paga e recebe de
-- volta só a sobra: o valor a devolver é menor que o recebido. Pago a menos, ou cobrança que já
-- não estava em aberto: devolve tudo, como antes.
ALTER TABLE pagamentos_online ADD COLUMN valor_a_devolver NUMERIC(12,2);
UPDATE pagamentos_online SET valor_a_devolver = recebido WHERE situacao IN ('A_DEVOLVER', 'DEVOLVIDO');
-- IS NOT NULL nunca dá NULL; e o valor, quando existe, fica entre zero e o recebido (ver CLAUDE.md).
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_a_devolver
    CHECK ((situacao IN ('A_DEVOLVER', 'DEVOLVIDO')) = (valor_a_devolver IS NOT NULL)
           AND (valor_a_devolver IS NULL OR (recebido IS NOT NULL AND valor_a_devolver > 0 AND valor_a_devolver <= recebido)));
