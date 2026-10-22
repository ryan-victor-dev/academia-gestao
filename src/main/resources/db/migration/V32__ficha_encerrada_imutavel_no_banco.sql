-- A ficha encerrada nunca mais muda nem é apagada (ADR 0027), agora também no banco, e não
-- só no código (diagnóstico da etapa 7, T1). O gatilho da V30 olhava só a linha nova: dava
-- para tirar uma divisão da encerrada (UPDATE ficha_id), apagar as divisões e os exercícios
-- dela, e trocar o aluno ou o autor de uma ficha.
--
-- Agora:
-- - fichas: a encerrada não muda; de nenhuma muda o aluno, a montagem ou quem montou;
-- - divisões e exercícios: nunca se alteram (a correção apaga os da ficha atual e grava
--   novos); acrescentar ou apagar, só na ficha atual;
-- - apagar a ficha, só o dono do banco (as permissões ficam no UsuarioDaAplicacaoNoBanco).
--   O dono também pode apagar divisões e exercícios de uma encerrada: é a limpeza dos
--   testes; a aplicação conecta com outro usuário.
CREATE OR REPLACE FUNCTION recusar_mudanca_em_ficha_encerrada() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    divisao   UUID;
    ficha     UUID;
    encerrada BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'fichas' THEN
        IF OLD.encerrada_em IS NOT NULL THEN
            RAISE EXCEPTION 'ficha encerrada não muda (fichas)';
        END IF;
        IF NEW.id <> OLD.id OR NEW.aluno_id <> OLD.aluno_id OR NEW.montada_em <> OLD.montada_em
                OR NEW.montada_por <> OLD.montada_por THEN
            RAISE EXCEPTION 'o aluno, a montagem e o autor da ficha não mudam';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'divisões e exercícios da ficha não se alteram: a correção grava novos (%)', TG_TABLE_NAME;
    END IF;
    IF TG_OP = 'DELETE' AND pg_has_role(current_user, (SELECT relowner FROM pg_class WHERE oid = TG_RELID), 'USAGE') THEN
        RETURN OLD;
    END IF;

    IF TG_TABLE_NAME = 'ficha_divisoes' AND TG_OP = 'INSERT' THEN
        ficha := NEW.ficha_id;
    ELSIF TG_TABLE_NAME = 'ficha_divisoes' THEN
        ficha := OLD.ficha_id;
    ELSE
        IF TG_OP = 'INSERT' THEN
            divisao := NEW.divisao_id;
        ELSE
            divisao := OLD.divisao_id;
        END IF;
        SELECT d.ficha_id INTO ficha FROM ficha_divisoes d WHERE d.id = divisao;
    END IF;
    SELECT f.encerrada_em IS NOT NULL INTO encerrada FROM fichas f WHERE f.id = ficha;
    IF encerrada THEN
        RAISE EXCEPTION 'ficha encerrada não muda (%)', TG_TABLE_NAME;
    END IF;
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    RETURN OLD;
END $$;

DROP TRIGGER tg_ficha_divisoes_encerrada_nao_muda ON ficha_divisoes;
CREATE TRIGGER tg_ficha_divisoes_encerrada_nao_muda BEFORE INSERT OR UPDATE OR DELETE ON ficha_divisoes
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_em_ficha_encerrada();
DROP TRIGGER tg_ficha_exercicios_encerrada_nao_muda ON ficha_exercicios;
CREATE TRIGGER tg_ficha_exercicios_encerrada_nao_muda BEFORE INSERT OR UPDATE OR DELETE ON ficha_exercicios
    FOR EACH ROW EXECUTE FUNCTION recusar_mudanca_em_ficha_encerrada();
