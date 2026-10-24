-- Cartão de crédito e de débito pelo app (ADR 0037): o aluno paga na página da empresa de
-- pagamentos (o link), e nenhum dado de cartão passa pelo sistema.
ALTER TABLE pagamentos_online ALTER COLUMN forma TYPE VARCHAR(7);
ALTER TABLE pagamentos_online DROP CONSTRAINT pagamentos_online_forma_check;
ALTER TABLE pagamentos_online ADD CONSTRAINT pagamentos_online_forma_check
    CHECK (forma IN ('PIX', 'BOLETO', 'CREDITO', 'DEBITO'));
ALTER TABLE pagamentos_online ADD COLUMN link_de_pagamento VARCHAR(500);

-- O Pix tem o copia e cola; o boleto, a linha digitável; o cartão, o link. IS NOT NULL nunca dá NULL.
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_dados;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_dados
    CHECK (CASE forma WHEN 'PIX' THEN pix_copia_e_cola IS NOT NULL
                      WHEN 'BOLETO' THEN boleto_linha_digitavel IS NOT NULL
                      ELSE link_de_pagamento IS NOT NULL END);

-- O ADMIN liga e desliga cada forma. Ligadas, como o Pix e o boleto (ADR 0034): só aparecem ao
-- aluno se a empresa também oferecer.
ALTER TABLE configuracao_pagamento_online ADD COLUMN credito_ligado BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE configuracao_pagamento_online ADD COLUMN debito_ligado BOOLEAN NOT NULL DEFAULT true;
