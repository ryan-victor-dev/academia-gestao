-- Código da transação no Pix e no cartão do balcão (ADR 0046, item 10). Sem ele, a recepção podia
-- registrar um pagamento que não recebeu; com ele, o ADMIN confere cada um com o extrato e com a
-- maquininha, e o mesmo comprovante não paga duas cobranças.
--
-- O código vem limpo da aplicação: sem espaços e em maiúsculas, de 1 a 100 caracteres.
ALTER TABLE cobrancas ADD COLUMN codigo_transacao VARCHAR(100);
ALTER TABLE lancamentos ADD COLUMN codigo_transacao VARCHAR(100);

-- Só no Pix e no cartão do balcão, e só no pagamento em vigor (o estorno limpa a cobrança).
ALTER TABLE cobrancas ADD CONSTRAINT ck_cobrancas_codigo_transacao CHECK (codigo_transacao IS NULL
    OR (status = 'PAGA' AND meio_de_pagamento IN ('PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO')
        AND codigo_transacao <> '' AND codigo_transacao = upper(codigo_transacao)
        AND codigo_transacao !~ '[\t\n\r ]'));
ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_codigo_transacao CHECK (codigo_transacao IS NULL
    OR (meio_de_pagamento IN ('PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO')
        AND codigo_transacao <> '' AND codigo_transacao = upper(codigo_transacao)
        AND codigo_transacao !~ '[\t\n\r ]'));

-- Todo pagamento novo no Pix e no cartão tem o código. NOT VALID: os de antes ficam sem ele (o
-- registro só aceita INSERT, então a regra vale para todo lançamento daqui em diante). O estorno
-- repete o código do pagamento, que pode ser nulo num pagamento de antes.
ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_codigo_obrigatorio CHECK (tipo <> 'PAGAMENTO'
    OR meio_de_pagamento NOT IN ('PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO') OR codigo_transacao IS NOT NULL) NOT VALID;

-- O Pix nunca repete entre as cobranças pagas; o estorno libera o código (decisão do dono).
CREATE UNIQUE INDEX uq_cobrancas_codigo_do_pix ON cobrancas (codigo_transacao)
    WHERE status = 'PAGA' AND meio_de_pagamento = 'PIX';

-- O código do cartão (NSU ou autorização) volta a valer em outro dia: não repete na mesma forma no
-- mesmo dia, no fuso da academia (o mesmo do Calendario; o FusoNoBancoTest confere).
CREATE UNIQUE INDEX uq_cobrancas_codigo_do_cartao_no_dia ON cobrancas
    (meio_de_pagamento, codigo_transacao, ((paga_em AT TIME ZONE 'America/Sao_Paulo')::date))
    WHERE status = 'PAGA' AND meio_de_pagamento IN ('CARTAO_DEBITO', 'CARTAO_CREDITO');
