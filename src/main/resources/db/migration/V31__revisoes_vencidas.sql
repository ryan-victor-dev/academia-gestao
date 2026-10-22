-- Lista das fichas atuais com a revisão vencida (ADR 0027): só as atuais com data de
-- revisão entram no índice, da revisão mais antiga para a mais nova.
CREATE INDEX ix_fichas_revisao ON fichas (revisao_em) WHERE encerrada_em IS NULL AND revisao_em IS NOT NULL;
