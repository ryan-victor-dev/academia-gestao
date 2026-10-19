-- Bloqueio por conta e IP (diagnóstico de 2026-10-18): 10 senhas erradas bloqueavam a conta
-- para qualquer IP, até para o dono com a senha certa. Quem soubesse só o e-mail mantinha o
-- ADMIN fora do sistema para sempre, com 10 tentativas a cada 15 minutos. Agora cada falha
-- guarda de onde veio: o bloqueio comum vale só para aquele IP naquela conta, e um teto bem
-- maior, somando todos os IPs, segura o ataque distribuído.
--
-- Vazio nas tentativas por IP (a chave já é o IP) e nas falhas gravadas antes desta versão:
-- essas contam só para o teto da conta e saem da janela em até 15 minutos.
ALTER TABLE tentativas_login ADD COLUMN ip VARCHAR(150);
ALTER TABLE tentativas_login ADD CONSTRAINT ck_tentativas_login_ip CHECK (tipo = 'CONTA' OR ip IS NULL);
