CREATE TABLE usuarios (
    id          UUID         PRIMARY KEY,
    nome        VARCHAR(150) NOT NULL,
    email       VARCHAR(150) NOT NULL,
    senha_hash  VARCHAR(100) NOT NULL,
    perfil      VARCHAR(20)  NOT NULL
                CHECK (perfil IN ('ADMIN', 'RECEPCAO', 'INSTRUTOR', 'ALUNO')),
    aluno_id    UUID         UNIQUE REFERENCES alunos (id),
    ativo       BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Perfil ALUNO precisa estar ligado a um aluno; os outros perfis, não.
    CONSTRAINT ck_usuarios_aluno_vinculado
        CHECK ((perfil = 'ALUNO') = (aluno_id IS NOT NULL))
);

-- E-mail único sem diferenciar maiúsculas: "Ana@x.com" e "ana@x.com" são o mesmo login.
CREATE UNIQUE INDEX uk_usuarios_email ON usuarios (lower(email));
