-- DEVOLVENDO (ADR 0036): a devolução pela empresa em andamento. O pagamento passa de A_DEVOLVER
-- para DEVOLVENDO antes da chamada à empresa; enquanto isso, "devolvido por fora" é recusado, e o
-- dinheiro não volta duas vezes (uma pela empresa, outra por fora).
ALTER TABLE pagamentos_online DROP CONSTRAINT pagamentos_online_situacao_check;
ALTER TABLE pagamentos_online ADD CONSTRAINT pagamentos_online_situacao_check
    CHECK (situacao IN ('AGUARDANDO', 'PAGO', 'EXPIRADO', 'CANCELADO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO'));

-- Devolvendo ainda tem o recebido, o motivo e o valor a devolver, como o A_DEVOLVER.
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_recebido;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_recebido
    CHECK ((situacao IN ('PAGO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO'))
           = (recebido IS NOT NULL AND recebido_em IS NOT NULL));
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_motivo;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_motivo
    CHECK ((situacao IN ('A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO'))
           = (motivo_da_devolucao IS NOT NULL AND btrim(motivo_da_devolucao) <> ''));
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_a_devolver;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_a_devolver
    CHECK ((situacao IN ('A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO')) = (valor_a_devolver IS NOT NULL)
           AND (valor_a_devolver IS NULL OR (recebido IS NOT NULL AND valor_a_devolver > 0
                                             AND valor_a_devolver <= recebido)));

-- A lista do ADMIN mostra os dois: o devolvendo parado (o sistema caiu no meio) pode ser repetido.
DROP INDEX ix_pagamentos_online_a_devolver;
CREATE INDEX ix_pagamentos_online_a_devolver ON pagamentos_online (recebido_em)
    WHERE situacao IN ('A_DEVOLVER', 'DEVOLVENDO');
