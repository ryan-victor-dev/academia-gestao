-- A consulta dos encerrados (ADR 0036): de hora em hora, os cancelados e expirados cujo prazo
-- acabou nos últimos dias, porque ainda podem ter sido pagos.
CREATE INDEX ix_pagamentos_online_encerrados ON pagamentos_online (provedor, valido_ate)
    WHERE situacao IN ('EXPIRADO', 'CANCELADO');
