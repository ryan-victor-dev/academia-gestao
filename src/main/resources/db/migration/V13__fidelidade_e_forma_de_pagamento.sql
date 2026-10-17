-- Regras que o dono define em cada plano (ADR 0018). Planos que já existem ficam sem
-- fidelidade e com pagamento mensal, que era o único jeito até aqui.
ALTER TABLE planos
    ADD COLUMN fidelidade       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Percentual sobre o saldo restante, cobrado de quem cancela antes do fim.
    ADD COLUMN percentual_multa NUMERIC(5,2),
    ADD COLUMN forma_pagamento  VARCHAR(10)  NOT NULL DEFAULT 'MENSAL',
    ADD CONSTRAINT ck_planos_forma_pagamento CHECK (forma_pagamento IN ('MENSAL', 'A_VISTA')),
    -- Com fidelidade, o percentual é obrigatório (0,01% a 100%); sem fidelidade, não existe.
    -- O IS NOT NULL é necessário: NULL > 0 dá NULL, e um CHECK que dá NULL deixa passar.
    ADD CONSTRAINT ck_planos_multa CHECK (
        (fidelidade AND percentual_multa IS NOT NULL AND percentual_multa > 0 AND percentual_multa <= 100)
        OR (NOT fidelidade AND percentual_multa IS NULL));
