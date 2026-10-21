-- Fichas de treino (etapa 7, ADR 0027): o instrutor monta, e o aluno consulta. Uma atual
-- por aluno; montar outra encerra a anterior, que fica no histórico e nunca mais muda.
CREATE TABLE fichas (
    id           UUID        PRIMARY KEY,
    aluno_id     UUID        NOT NULL REFERENCES alunos (id),
    -- Dia em que o instrutor quer rever a ficha (opcional). Vencida, ela continua valendo.
    revisao_em   DATE,
    montada_em   TIMESTAMPTZ NOT NULL,
    montada_por  UUID        NOT NULL REFERENCES usuarios (id),
    -- Última correção (a trilha guarda todas); vazio = nunca corrigida.
    corrigida_em TIMESTAMPTZ,
    -- Vazio = a ficha atual.
    encerrada_em TIMESTAMPTZ,
    -- Versão para o ETag/If-Match da correção (ADR 0009).
    versao       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_fichas_corrigida CHECK (corrigida_em >= montada_em),
    CONSTRAINT ck_fichas_encerrada CHECK (encerrada_em >= montada_em AND encerrada_em >= corrigida_em)
);

-- Uma só ficha atual por aluno, mesmo que um erro no código (ou uma corrida) tente duas.
CREATE UNIQUE INDEX uk_fichas_atual_por_aluno ON fichas (aluno_id) WHERE encerrada_em IS NULL;
-- Histórico do aluno, da mais nova para a mais antiga.
CREATE INDEX ix_fichas_aluno ON fichas (aluno_id, montada_em DESC);

-- Treino A, B, C...: até 7 por ficha, em ordem.
CREATE TABLE ficha_divisoes (
    id       UUID        PRIMARY KEY,
    ficha_id UUID        NOT NULL REFERENCES fichas (id),
    posicao  INT         NOT NULL CHECK (posicao BETWEEN 1 AND 7),
    nome     VARCHAR(40) NOT NULL CHECK (btrim(nome) <> ''),

    -- Conferida no fim da transação: a correção grava as divisões novas antes de apagar as
    -- antigas, e as posições coincidem no meio do caminho.
    CONSTRAINT uk_ficha_divisoes_posicao UNIQUE (ficha_id, posicao) DEFERRABLE INITIALLY DEFERRED
);

-- Exercícios de uma divisão, em ordem: até 20. O nome é copiado do catálogo, como a matrícula
-- copia o do plano: renomear o exercício não muda o histórico.
CREATE TABLE ficha_exercicios (
    id                UUID         PRIMARY KEY,
    divisao_id        UUID         NOT NULL REFERENCES ficha_divisoes (id),
    posicao           INT          NOT NULL CHECK (posicao BETWEEN 1 AND 20),
    exercicio_id      UUID         NOT NULL REFERENCES exercicios (id),
    nome              VARCHAR(80)  NOT NULL CHECK (btrim(nome) <> ''),
    series            INT          NOT NULL CHECK (series BETWEEN 1 AND 20),
    -- Texto, e não número: "8 a 12", "até a falha", "20 kg", "elástico vermelho".
    repeticoes        VARCHAR(20)  NOT NULL CHECK (btrim(repeticoes) <> ''),
    carga             VARCHAR(30)  CHECK (btrim(carga) <> ''),
    descanso_segundos INT          CHECK (descanso_segundos BETWEEN 0 AND 600),
    observacao        VARCHAR(500) CHECK (btrim(observacao) <> ''),

    CONSTRAINT uk_ficha_exercicios_posicao UNIQUE (divisao_id, posicao)
);

-- A ficha encerrada nunca mais muda: o banco recusa alterá-la ou acrescentar divisões e
-- exercícios a ela, mesmo que um erro no código tente. (Apagar continua possível só para a
-- limpeza dos testes; a aplicação nunca apaga uma ficha.)
CREATE FUNCTION recusar_mudanca_em_ficha_encerrada() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    encerrada BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'fichas' THEN
        encerrada := OLD.encerrada_em IS NOT NULL;
    ELSIF TG_TABLE_NAME = 'ficha_divisoes' THEN
        SELECT f.encerrada_em IS NOT NULL INTO encerrada FROM fichas f WHERE f.id = NEW.ficha_id;
    ELSE
        SELECT f.encerrada_em IS NOT NULL INTO encerrada
        FROM ficha_divisoes d JOIN fichas f ON f.id = d.ficha_id WHERE d.id = NEW.divisao_id;
    END IF;
    IF encerrada THEN
        RAISE EXCEPTION 'ficha encerrada não muda (%)', TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER tg_fichas_encerrada_nao_muda BEFORE UPDATE ON fichas
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_em_ficha_encerrada();
CREATE TRIGGER tg_ficha_divisoes_encerrada_nao_muda BEFORE INSERT OR UPDATE ON ficha_divisoes
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_em_ficha_encerrada();
CREATE TRIGGER tg_ficha_exercicios_encerrada_nao_muda BEFORE INSERT OR UPDATE ON ficha_exercicios
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_em_ficha_encerrada();

ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA'));
