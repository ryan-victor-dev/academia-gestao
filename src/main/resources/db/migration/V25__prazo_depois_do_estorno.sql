-- Estorno (decisão do dono, ADR 0026): até o estorno, a academia dava a cobrança por paga.
-- Acréscimo, juros e bloqueio contam do dia do último estorno, se ele vier depois do vencimento.
ALTER TABLE cobrancas ADD COLUMN estornada_no_dia DATE;
-- O prazo para pagar sem atraso; GREATEST ignora o nulo (sem estorno, é o vencimento).
-- A mesma conta de Cobranca.getPrazo().
ALTER TABLE cobrancas ADD COLUMN prazo DATE GENERATED ALWAYS AS (GREATEST(vencimento, estornada_no_dia)) STORED;

-- Os estornos de antes (só a trilha guarda todos: o registro de lançamentos começou na V24).
UPDATE cobrancas c
SET estornada_no_dia = e.dia
FROM (SELECT entidade_id, max((momento AT TIME ZONE 'America/Sao_Paulo')::date) AS dia
      FROM auditoria
      WHERE entidade = 'COBRANCA' AND acao = 'ESTORNADA'
      GROUP BY entidade_id) e
WHERE c.id = e.entidade_id;

-- A lista de atrasadas e o "pode treinar?" passam a olhar o prazo.
DROP INDEX ix_cobrancas_pendentes;
CREATE INDEX ix_cobrancas_pendentes ON cobrancas (prazo) WHERE status = 'PENDENTE';
