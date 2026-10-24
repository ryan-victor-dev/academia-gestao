-- Pagamento online (ADR 0034): Pix online e boleto, recebidos por uma empresa de pagamentos.
-- O dinheiro cai na conta, não na gaveta: o lançamento online não tem caixa, e o do balcão
-- continua exigindo o caixa aberto do dia (gatilho da V35).
ALTER TABLE cobrancas DROP CONSTRAINT cobrancas_meio_de_pagamento_check;
ALTER TABLE cobrancas ADD CONSTRAINT cobrancas_meio_de_pagamento_check
    CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'PIX_ONLINE', 'BOLETO'));

ALTER TABLE lancamentos DROP CONSTRAINT lancamentos_meio_de_pagamento_check;
ALTER TABLE lancamentos ADD CONSTRAINT lancamentos_meio_de_pagamento_check
    CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'PIX_ONLINE', 'BOLETO'));

-- Antes: todo lançamento novo com caixa. Agora: com caixa se, e só se, for do balcão. Continua
-- NOT VALID: os lançamentos de antes do caixa (V35) ficam sem ele.
ALTER TABLE lancamentos DROP CONSTRAINT ck_lancamentos_no_caixa;
ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_no_caixa
    CHECK ((caixa_id IS NOT NULL) = (meio_de_pagamento NOT IN ('PIX_ONLINE', 'BOLETO'))) NOT VALID;
