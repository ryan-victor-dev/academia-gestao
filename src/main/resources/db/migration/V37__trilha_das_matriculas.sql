-- Trilha das matrículas (ADR 0031): criar, trancar, registrar a volta, cancelar e trocar de
-- plano entram na auditoria, como as alterações de planos, alunos e cobranças.
ALTER TABLE auditoria DROP CONSTRAINT auditoria_entidade_check;
ALTER TABLE auditoria ADD CONSTRAINT auditoria_entidade_check
    CHECK (entidade IN ('PLANO', 'ALUNO', 'USUARIO', 'COBRANCA', 'CONFIGURACAO', 'EXERCICIO', 'FICHA', 'CAIXA',
                        'MATRICULA'));
