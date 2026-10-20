-- Teto do valor mensal: R$ 100.000 (decisão do dono, 2026-10-20). Antes, o plano aceitava até
-- 99.999.999,99, e o total à vista (valor × até 60 meses) estourava a coluna da cobrança (500).
-- Com o teto, o à vista mais caro é de 6 milhões, e cabe em NUMERIC(10,2).
-- Um plano ou matrícula acima do teto faz esta migração falhar: ajuste o valor antes.
ALTER TABLE planos ADD CONSTRAINT ck_planos_valor_maximo CHECK (valor_mensal <= 100000);
ALTER TABLE matriculas ADD CONSTRAINT ck_matriculas_valor_maximo CHECK (valor_mensal <= 100000);

-- Os juros crescem com os dias de atraso, sem limite: o pago ganha folga para nunca estourar
-- (6 milhões com 10% ao mês levariam mais de mil anos para passar de NUMERIC(12,2)).
ALTER TABLE cobrancas ALTER COLUMN pago_juros TYPE NUMERIC(12,2), ALTER COLUMN pago_total TYPE NUMERIC(12,2);
