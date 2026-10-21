-- Paga e fora do cronograma (decisão do dono, ADR 0026): a parcela paga adiantada de uma
-- matrícula encerrada continua paga, mas um estorno a cancela em vez de fazê-la voltar a dever.
ALTER TABLE cobrancas ADD COLUMN fora_do_cronograma BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE cobrancas ADD CONSTRAINT ck_cobrancas_fora_do_cronograma
    CHECK (NOT fora_do_cronograma OR status <> 'PENDENTE');

-- As de antes: a mesma conta do Cronograma. Cancelamento fica com as que vencem até o dia,
-- inclusive; troca, com as de antes do dia. A multa nunca sai.
CREATE TEMPORARY TABLE fora ON COMMIT DROP AS
SELECT c.id
FROM cobrancas c JOIN matriculas m ON m.id = c.matricula_id
WHERE c.tipo IN ('MENSALIDADE', 'A_VISTA')
  AND ((m.cancelada_em IS NOT NULL AND c.vencimento > (m.cancelada_em AT TIME ZONE 'America/Sao_Paulo')::date)
       OR (m.trocada_em IS NOT NULL AND c.vencimento >= m.trocada_em));

UPDATE cobrancas SET fora_do_cronograma = true
WHERE status = 'PAGA' AND id IN (SELECT id FROM fora);

-- Já estornadas e de volta em aberto (o defeito): canceladas pelo sistema, como no encerramento.
UPDATE cobrancas
SET status = 'CANCELADA', cancelada_em = now(), cancelada_por = NULL,
    motivo_cancelamento = 'Estornada depois de sair do cronograma (matrícula encerrada): não é mais devida',
    versao = versao + 1
WHERE status = 'PENDENTE' AND id IN (SELECT id FROM fora);
