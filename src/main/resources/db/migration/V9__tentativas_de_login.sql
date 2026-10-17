-- Contadores do limite de tentativas de login (força bruta). Ficam no banco para
-- sobreviver a reinícios da aplicação. Guardam IP e e-mail, então só pelo tempo da
-- maior janela (15 min): uma limpeza periódica apaga o resto.
CREATE TABLE tentativas_login (
    id      BIGSERIAL    PRIMARY KEY,
    tipo    VARCHAR(5)   NOT NULL CHECK (tipo IN ('IP', 'CONTA')),
    chave   VARCHAR(150) NOT NULL,
    momento TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_tentativas_login_chave ON tentativas_login (tipo, chave, momento);
CREATE INDEX ix_tentativas_login_momento ON tentativas_login (momento);
