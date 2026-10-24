-- Um Pix e um boleto aguardando por cobrança, no máximo (ADR 0035): dois pedidos ao mesmo tempo
-- (duas abas, dois toques) geravam dois. Com o índice, o segundo a gravar esbarra nele, cancela
-- o seu na empresa e devolve ao aluno o que ficou.

-- Os duplicados de antes do índice: fica o mais novo; os outros viram CANCELADO, que ainda
-- recebe se alguém pagar (o dinheiro não se perde: paga ou fica a devolver).
UPDATE pagamentos_online p SET situacao = 'CANCELADO'
WHERE situacao = 'AGUARDANDO'
  AND EXISTS (SELECT 1 FROM pagamentos_online q
              WHERE q.cobranca_id = p.cobranca_id AND q.forma = p.forma AND q.situacao = 'AGUARDANDO'
                AND (q.gerado_em, q.id) > (p.gerado_em, p.id));

CREATE UNIQUE INDEX uq_pagamentos_online_aguardando ON pagamentos_online (cobranca_id, forma)
    WHERE situacao = 'AGUARDANDO';
