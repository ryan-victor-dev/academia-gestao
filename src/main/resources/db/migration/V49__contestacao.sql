-- Contestação (ADR 0037): o aluno contesta no banco, que devolve o dinheiro a ele semanas depois.
-- O pagamento online vira CONTESTADO; se o dinheiro tinha pago a cobrança, ela volta em aberto
-- (decisão do dono), e cobranca_reaberta diz se foi o caso.
ALTER TABLE pagamentos_online DROP CONSTRAINT pagamentos_online_situacao_check;
ALTER TABLE pagamentos_online ADD CONSTRAINT pagamentos_online_situacao_check
    CHECK (situacao IN ('AGUARDANDO', 'PAGO', 'EXPIRADO', 'CANCELADO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO',
                        'CONTESTADO'));
ALTER TABLE pagamentos_online ADD COLUMN contestado_em TIMESTAMPTZ;
ALTER TABLE pagamentos_online ADD COLUMN cobranca_reaberta BOOLEAN;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_contestado
    CHECK ((situacao = 'CONTESTADO') = (contestado_em IS NOT NULL AND cobranca_reaberta IS NOT NULL));

-- O contestado veio de um recebido (PAGO, A_DEVOLVER, DEVOLVENDO ou DEVOLVIDO): tem o recebido e
-- guarda o que já tinha (motivo, valor a devolver, quem devolveu). CASE, e não "=", porque no
-- CONTESTADO esses campos podem estar ou não; nenhum ramo dá NULL (ver CLAUDE.md).
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_recebido;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_recebido
    CHECK ((situacao IN ('PAGO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO', 'CONTESTADO'))
           = (recebido IS NOT NULL AND recebido_em IS NOT NULL));
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_motivo;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_motivo
    CHECK (CASE WHEN situacao IN ('A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO')
                    THEN motivo_da_devolucao IS NOT NULL AND btrim(motivo_da_devolucao) <> ''
                WHEN situacao = 'CONTESTADO'
                    THEN motivo_da_devolucao IS NULL OR btrim(motivo_da_devolucao) <> ''
                ELSE motivo_da_devolucao IS NULL END);
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_a_devolver;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_a_devolver
    CHECK (CASE WHEN situacao IN ('A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO') THEN valor_a_devolver IS NOT NULL
                WHEN situacao = 'CONTESTADO' THEN true
                ELSE valor_a_devolver IS NULL END
           AND (valor_a_devolver IS NULL OR (recebido IS NOT NULL AND valor_a_devolver > 0
                                             AND valor_a_devolver <= recebido)));
ALTER TABLE pagamentos_online DROP CONSTRAINT ck_pagamentos_online_devolvido;
ALTER TABLE pagamentos_online ADD CONSTRAINT ck_pagamentos_online_devolvido
    CHECK (CASE WHEN situacao = 'DEVOLVIDO' THEN devolvido_em IS NOT NULL AND devolvido_por IS NOT NULL
                WHEN situacao = 'CONTESTADO' THEN (devolvido_em IS NULL) = (devolvido_por IS NULL)
                ELSE devolvido_em IS NULL AND devolvido_por IS NULL END);

-- A consulta diária das contestações (cartões recebidos nos últimos meses) e a lista do ADMIN.
CREATE INDEX ix_pagamentos_online_recebidos ON pagamentos_online (provedor, recebido_em)
    WHERE situacao IN ('PAGO', 'A_DEVOLVER', 'DEVOLVENDO', 'DEVOLVIDO');
CREATE INDEX ix_pagamentos_online_contestados ON pagamentos_online (contestado_em) WHERE situacao = 'CONTESTADO';
