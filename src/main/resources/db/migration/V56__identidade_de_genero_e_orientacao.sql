-- Identidade de gênero e orientação sexual (ADR 0047, decisão 2). Opcionais, e orientação sexual é dado
-- pessoal sensível (LGPD, art. 5º, II): só o ADMIN e a própria pessoa veem. Ficam em tabelas próprias, e
-- não no cadastro, para não sair junto com o aluno que a recepção e o instrutor consultam, nem com o retrato
-- da trilha. Uma linha por pessoa; sem linha (ou com os dois nulos), a pessoa não respondeu.
--
-- O aluno guarda pelo cadastro (ele pode não ter login); a equipe, pelo login. Excluir o cadastro nunca
-- usado (ADR 0043) leva a linha junto: ela não é histórico.
CREATE TABLE identidade_dos_alunos (
    aluno_id                   UUID         PRIMARY KEY REFERENCES alunos (id) ON DELETE CASCADE,
    identidade_de_genero       VARCHAR(30)  CHECK (identidade_de_genero IN ('MULHER_CIS', 'HOMEM_CIS', 'MULHER_TRANS',
        'HOMEM_TRANS', 'TRAVESTI', 'NAO_BINARIA', 'GENERO_FLUIDO', 'AGENERO', 'OUTRA', 'PREFIRO_NAO_INFORMAR')),
    identidade_de_genero_outra VARCHAR(50),
    orientacao_sexual          VARCHAR(30)  CHECK (orientacao_sexual IN ('HETEROSSEXUAL', 'LESBICA', 'GAY', 'BISSEXUAL',
        'PANSEXUAL', 'ASSEXUAL', 'DEMISSEXUAL', 'QUEER', 'OUTRA', 'PREFIRO_NAO_INFORMAR')),
    orientacao_sexual_outra    VARCHAR(50),
    atualizado_em              TIMESTAMPTZ  NOT NULL,
    -- A descrição só acompanha "outra", e é obrigatória nela (sem espaços nas pontas, nunca vazia). IS NOT
    -- DISTINCT FROM, e não "=": com a opção nula, "=" daria NULL, e o CHECK deixaria passar uma descrição solta.
    CONSTRAINT ck_identidade_dos_alunos_genero_outra CHECK ((identidade_de_genero IS NOT DISTINCT FROM 'OUTRA')
        = (identidade_de_genero_outra IS NOT NULL AND identidade_de_genero_outra <> ''
           AND identidade_de_genero_outra = btrim(identidade_de_genero_outra))),
    CONSTRAINT ck_identidade_dos_alunos_orientacao_outra CHECK ((orientacao_sexual IS NOT DISTINCT FROM 'OUTRA')
        = (orientacao_sexual_outra IS NOT NULL AND orientacao_sexual_outra <> ''
           AND orientacao_sexual_outra = btrim(orientacao_sexual_outra)))
);

CREATE TABLE identidade_da_equipe (
    usuario_id                 UUID         PRIMARY KEY REFERENCES usuarios (id) ON DELETE CASCADE,
    identidade_de_genero       VARCHAR(30)  CHECK (identidade_de_genero IN ('MULHER_CIS', 'HOMEM_CIS', 'MULHER_TRANS',
        'HOMEM_TRANS', 'TRAVESTI', 'NAO_BINARIA', 'GENERO_FLUIDO', 'AGENERO', 'OUTRA', 'PREFIRO_NAO_INFORMAR')),
    identidade_de_genero_outra VARCHAR(50),
    orientacao_sexual          VARCHAR(30)  CHECK (orientacao_sexual IN ('HETEROSSEXUAL', 'LESBICA', 'GAY', 'BISSEXUAL',
        'PANSEXUAL', 'ASSEXUAL', 'DEMISSEXUAL', 'QUEER', 'OUTRA', 'PREFIRO_NAO_INFORMAR')),
    orientacao_sexual_outra    VARCHAR(50),
    atualizado_em              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_identidade_da_equipe_genero_outra CHECK ((identidade_de_genero IS NOT DISTINCT FROM 'OUTRA')
        = (identidade_de_genero_outra IS NOT NULL AND identidade_de_genero_outra <> ''
           AND identidade_de_genero_outra = btrim(identidade_de_genero_outra))),
    CONSTRAINT ck_identidade_da_equipe_orientacao_outra CHECK ((orientacao_sexual IS NOT DISTINCT FROM 'OUTRA')
        = (orientacao_sexual_outra IS NOT NULL AND orientacao_sexual_outra <> ''
           AND orientacao_sexual_outra = btrim(orientacao_sexual_outra)))
);
