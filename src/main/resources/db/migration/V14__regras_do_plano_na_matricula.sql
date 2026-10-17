-- A matrícula copia as regras do plano no dia em que é feita (ADR 0018), como já copia
-- nome, valor e duração. Matrículas anteriores foram feitas sem fidelidade e com pagamento
-- mensal, que era o único jeito até aqui.
ALTER TABLE matriculas
    ADD COLUMN fidelidade         BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN percentual_multa   NUMERIC(5,2),
    ADD COLUMN forma_pagamento    VARCHAR(10)   NOT NULL DEFAULT 'MENSAL',
    -- Calculada e gravada quando uma matrícula com fidelidade é encerrada antes do fim;
    -- cobrá-la é da etapa 6.
    ADD COLUMN multa_cancelamento NUMERIC(10,2),
    ADD CONSTRAINT ck_matriculas_forma_pagamento CHECK (forma_pagamento IN ('MENSAL', 'A_VISTA')),
    -- Mesma regra do plano. O IS NOT NULL é necessário: um CHECK que dá NULL deixa passar.
    ADD CONSTRAINT ck_matriculas_percentual_multa CHECK (
        (fidelidade AND percentual_multa IS NOT NULL AND percentual_multa > 0 AND percentual_multa <= 100)
        OR (NOT fidelidade AND percentual_multa IS NULL)),
    -- Multa só existe em matrícula com fidelidade, e nunca é negativa.
    ADD CONSTRAINT ck_matriculas_multa CHECK (
        multa_cancelamento IS NULL OR (fidelidade AND multa_cancelamento >= 0));
