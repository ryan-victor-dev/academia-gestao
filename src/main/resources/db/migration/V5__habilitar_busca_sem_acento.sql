-- unaccent remove acentos ("José" vira "Jose"), para a busca por nome ignorar acentuação.
-- Vem junto com o PostgreSQL (contrib); instalar exige um usuário com permissão de
-- criar extensões, como o dono do banco no Docker Compose.
CREATE EXTENSION IF NOT EXISTS unaccent;
