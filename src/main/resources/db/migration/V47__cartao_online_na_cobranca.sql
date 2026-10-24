-- Cartão de crédito e de débito pelo app (ADR 0037): como o Pix e o boleto online (V41), o
-- dinheiro cai na conta pela empresa de pagamentos, sem caixa. O cartão do balcão continua
-- CARTAO_CREDITO e CARTAO_DEBITO, com caixa.
ALTER TABLE cobrancas DROP CONSTRAINT cobrancas_meio_de_pagamento_check;
ALTER TABLE cobrancas ADD CONSTRAINT cobrancas_meio_de_pagamento_check
    CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'PIX_ONLINE', 'BOLETO',
                                 'CREDITO_ONLINE', 'DEBITO_ONLINE'));

ALTER TABLE lancamentos DROP CONSTRAINT lancamentos_meio_de_pagamento_check;
ALTER TABLE lancamentos ADD CONSTRAINT lancamentos_meio_de_pagamento_check
    CHECK (meio_de_pagamento IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO', 'PIX_ONLINE', 'BOLETO',
                                 'CREDITO_ONLINE', 'DEBITO_ONLINE'));

-- Com caixa se, e só se, for do balcão (V41). Continua NOT VALID: os de antes do caixa (V35).
ALTER TABLE lancamentos DROP CONSTRAINT ck_lancamentos_no_caixa;
ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_no_caixa
    CHECK ((caixa_id IS NOT NULL)
           = (meio_de_pagamento NOT IN ('PIX_ONLINE', 'BOLETO', 'CREDITO_ONLINE', 'DEBITO_ONLINE'))) NOT VALID;
