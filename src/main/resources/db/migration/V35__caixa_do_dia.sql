-- Caixa do dia (ADR 0031): um por dia para a academia, aberto com o troco e fechado com a
-- contagem do dinheiro. Pagamento e estorno só entram no caixa aberto do dia em que são
-- feitos; cada fechamento e cada reabertura ficam no histórico (só INSERT para a aplicação,
-- ver UsuarioDaAplicacaoNoBanco).
CREATE TABLE caixas (
    id         UUID          PRIMARY KEY,
    -- O dia no fuso da academia: um caixa só por dia (duas aberturas ao mesmo tempo: uma ganha).
    dia        DATE          NOT NULL UNIQUE,
    troco      NUMERIC(10,2) NOT NULL CHECK (troco >= 0 AND troco <= 100000),
    situacao   VARCHAR(7)    NOT NULL CHECK (situacao IN ('ABERTO', 'FECHADO')),
    aberto_em  TIMESTAMPTZ   NOT NULL,
    aberto_por UUID          NOT NULL REFERENCES usuarios (id)
);
-- Para conferir, ao abrir, se ficou algum dia anterior aberto.
CREATE INDEX ix_caixas_abertos ON caixas (dia) WHERE situacao = 'ABERTO';

CREATE TABLE fechamentos_de_caixa (
    id            UUID          PRIMARY KEY,
    caixa_id      UUID          NOT NULL REFERENCES caixas (id),
    -- O dinheiro contado na gaveta; o esperado = troco + dinheiro recebido − estornado em dinheiro.
    contado       NUMERIC(12,2) NOT NULL CHECK (contado >= 0),
    esperado      NUMERIC(12,2) NOT NULL,
    diferenca     NUMERIC(13,2) NOT NULL,
    justificativa VARCHAR(500),
    fechado_em    TIMESTAMPTZ   NOT NULL,
    fechado_por   UUID          NOT NULL REFERENCES usuarios (id),

    CONSTRAINT ck_fechamentos_diferenca CHECK (diferenca = contado - esperado),
    -- Diferença diferente de zero exige justificativa escrita (decisão do dono).
    CONSTRAINT ck_fechamentos_justificativa CHECK (justificativa IS NULL OR btrim(justificativa) <> ''),
    CONSTRAINT ck_fechamentos_diferenca_justificada CHECK (diferenca = 0 OR justificativa IS NOT NULL)
);
CREATE INDEX ix_fechamentos_caixa ON fechamentos_de_caixa (caixa_id, fechado_em);

CREATE TABLE reaberturas_de_caixa (
    id           UUID         PRIMARY KEY,
    caixa_id     UUID         NOT NULL REFERENCES caixas (id),
    motivo       VARCHAR(500) NOT NULL CHECK (btrim(motivo) <> ''),
    reaberto_em  TIMESTAMPTZ  NOT NULL,
    reaberto_por UUID         NOT NULL REFERENCES usuarios (id)
);
CREATE INDEX ix_reaberturas_caixa ON reaberturas_de_caixa (caixa_id, reaberto_em);

-- O caixa em que o dinheiro entrou ou saiu. Os lançamentos de antes do caixa ficam sem ele
-- (NOT VALID: a trava vale só para as linhas novas).
ALTER TABLE lancamentos ADD COLUMN caixa_id UUID REFERENCES caixas (id);
ALTER TABLE lancamentos ADD CONSTRAINT ck_lancamentos_no_caixa CHECK (caixa_id IS NOT NULL) NOT VALID;
CREATE INDEX ix_lancamentos_caixa ON lancamentos (caixa_id);

-- Também no banco, e não só no código: o lançamento entra só no caixa aberto do dia em que é
-- registrado. A trava contra a corrida com o fechamento é a linha do caixa (FOR SHARE no
-- pagamento, FOR UPDATE no fechamento); este gatilho pega um caminho que esqueça dela.
CREATE FUNCTION recusar_lancamento_fora_do_caixa() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    caixa caixas%ROWTYPE;
BEGIN
    -- Sem caixa: quem recusa é o ck_lancamentos_no_caixa (cada trava com o seu teste).
    IF NEW.caixa_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO caixa FROM caixas WHERE id = NEW.caixa_id;
    IF caixa.situacao IS DISTINCT FROM 'ABERTO' THEN
        RAISE EXCEPTION 'lançamento só no caixa aberto';
    END IF;
    IF caixa.dia <> (NEW.registrado_em AT TIME ZONE 'America/Sao_Paulo')::date THEN
        RAISE EXCEPTION 'lançamento só no caixa do dia em que é registrado';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_lancamentos_no_caixa_aberto BEFORE INSERT ON lancamentos
    FOR EACH ROW EXECUTE FUNCTION recusar_lancamento_fora_do_caixa();

-- Do caixa só muda a situação: o dia, o troco e quem abriu ficam (troco errado vai na
-- justificativa do fechamento, decisão do dono).
CREATE FUNCTION recusar_mudanca_na_abertura_do_caixa() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.dia <> OLD.dia OR NEW.troco <> OLD.troco OR NEW.aberto_em <> OLD.aberto_em
            OR NEW.aberto_por <> OLD.aberto_por THEN
        RAISE EXCEPTION 'do caixa só muda a situação';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_caixas_so_muda_a_situacao BEFORE UPDATE ON caixas
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_na_abertura_do_caixa();

ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA', 'CAIXA'));
