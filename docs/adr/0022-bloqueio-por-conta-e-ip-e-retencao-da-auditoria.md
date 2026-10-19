# ADR 0022 — Bloqueio de login por conta e IP, e retenção da trilha de auditoria

- **Status:** Aceito (substitui o bloqueio por conta do ADR 0012; completa a trilha do ADR 0021)
- **Data:** 2026-10-18

## Contexto

O diagnóstico de 2026-10-18 (segurança, arquitetura e escalabilidade, com ataques e
carga numa instalação isolada) encontrou dois problemas de gravidade média. Este ADR
registra como foram resolvidos e as decisões do dono do projeto. Cada correção foi
comprovada antes, testada com mutação (o teste falha quando a regra é quebrada) e
validada no Docker.

## Decisões

### 1. Senhas erradas bloqueiam só o IP de onde vieram
- **Antes:** 10 senhas erradas em 15 minutos bloqueavam a conta para qualquer IP, até
  para o dono com a senha certa. O ADR 0012 aceitou o risco como "trava por 15
  minutos", mas renovar o bloqueio custava 10 requisições a cada 15 minutos, muito
  abaixo do limite de 20 por minuto por IP: quem soubesse só o e-mail mantinha qualquer
  pessoa, inclusive o ADMIN, fora do sistema por tempo indeterminado, de um único IP.
- **Regra (decidida pelo dono):**
  - 10 senhas erradas de um mesmo IP em 15 minutos bloqueiam **esse IP nessa conta**
    (`academia.login.falhas-por-conta-e-ip`). Dos outros IPs, o dono continua entrando.
  - 100 senhas erradas na conta em 15 minutos, **somando todos os IPs**, bloqueiam a
    conta inteira (`academia.login.falhas-por-conta-no-total`): o teto contra o ataque
    espalhado por muitas máquinas.
  - Continua: 20 tentativas por minuto por IP; durante o bloqueio, nem a senha certa
    entra (senão o atacante saberia que acertou); a troca de senha passa pelos mesmos
    limites.
- **Login certo zera só as falhas do próprio IP.** As dos outros IPs ficam: se a senha
  certa do dono zerasse tudo, o ataque espalhado ganharia um teto novo a cada vez que
  ele entrasse.
- Cada falha guarda o IP de origem (`tentativas_login.ip`, V19). As gravadas antes da
  V19, sem IP, contam só para o teto e saem da janela em até 15 minutos.
- **Depois (Docker):** 10 senhas erradas pelo Caddy; a senha certa do mesmo IP recebe
  429 com `Retry-After`, e a de outro IP entra.

### 2. Histórico da auditoria pelo id
- **Antes:** o índice da trilha (V18) começa pela entidade, e a consulta só com
  `entidadeId` não conseguia usá-lo. Com um milhão de registros (anos de uso), cada
  histórico varria a tabela inteira: 11 req/s, p50 de 1,6 s com 16 clientes.
- **Regra:** índice em `(entidade_id, id DESC)` (V20); o da V18 continua para a listagem
  só por entidade.
- **Depois:** com o mesmo milhão de registros, 3.152 req/s, p50 de 2,1 ms.

### 3. Retenção de 5 anos da trilha
- **Antes:** a trilha guardava valores antigos de dados pessoais (CPF, telefone,
  e-mail) sem prazo (pendência do ADR 0021, LGPD) e crescia sem limite: 277 MB por
  milhão de registros, 5 vezes a tabela de alunos.
- **Regra (decidida pelo dono):** registros com mais de **5 anos** são apagados todo dia
  às 4h30 (depois do backup), em lotes de até 10 mil. 5 anos cobrem o prazo do Código de
  Defesa do Consumidor para reclamar de uma cobrança e os prazos fiscais comuns. O
  horário pode ser trocado em `academia.auditoria.horario-da-retencao`.
- **Desenho:** o usuário da aplicação no banco continua **sem `DELETE`** na trilha. Quem
  apaga é a função `apagar_auditoria_vencida` (V21), criada pelo dono do banco com
  `SECURITY DEFINER` e `search_path` fixo, que só alcança o que passou do prazo; a
  aplicação só tem `EXECUTE` nela, e ninguém mais (o `EXECUTE` padrão de `PUBLIC` é
  revogado). O prazo fica na função, e não numa configuração da aplicação: nem uma
  injeção de SQL apaga o histórico recente, e mudar o prazo exige uma migração nova.
- **Depois:** instalação isolada com um milhão de registros, um terço deles vencidos:
  333.334 apagados em ~9 s, os da beira do prazo ficaram, e a leitura de planos seguiu
  em 2.307 req/s (p99 47 ms) durante a limpeza. No Docker, a aplicação executa a função
  e o `DELETE` direto continua recusado.

## Consequências

- Com 10 ou mais IPs, um atacante ainda consegue bloquear uma conta inteira (100
  falhas). É o preço do teto contra a força bruta distribuída; o dono decidiu por ele.
- O espaço dos registros apagados volta a ser usado pelos novos, mas o arquivo da tabela
  não encolhe sozinho (só com `VACUUM FULL`, que trava a tabela).
- O que sai da trilha ainda fica nos backups pelo prazo deles (7 diários e 4 semanais).
- A listagem da trilha sem filtro ainda conta todos os registros a cada página (~27 ms
  com um milhão); a retenção limita esse tamanho.
