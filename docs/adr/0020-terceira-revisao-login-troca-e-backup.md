# ADR 0020 — Terceira revisão (2026-10-17): login sob carga, troca de plano e backup

- **Status:** Aceito (complementa os ADRs 0012, 0014 e 0018; substitui a regra de multa na
  troca do ADR 0018)
- **Data:** 2026-10-17

## Contexto

Antes da etapa 6, o sistema inteiro passou por uma revisão: validação funcional (verify,
e2e, mutação no domínio todo), segurança (dependências, imagens, segredos no histórico,
ataques reais contra o Docker, lógica de negócio), arquitetura e escalabilidade (carga
numa instalação isolada com 50 mil alunos). Três achados foram classificados como graves
e corrigidos aqui; cada um foi comprovado antes da correção e de novo depois.

## Achados graves e correções

### 1. Login simultâneo esgotava o pool de conexões (disponibilidade)
- **Comprovado:** 30 tentativas de login simultâneas, de um único IP e pelo Caddy, sem
  estar logado, fizeram um usuário legítimo esperar 29,4 s; as tentativas terminavam em
  500 depois de 30 s. Bastavam ~10 logins ao mesmo tempo, inclusive sem ataque.
- **Causa:** o login rodava numa transação (uma conexão) e o limitador de tentativas grava
  numa transação própria (`REQUIRES_NEW`, segunda conexão); o BCrypt rodava com a primeira
  presa. Com o pool de 10 conexões, os 10 logins ficavam esperando a segunda. O limite de
  tentativas não ajudava, porque a checagem dele estava dentro do trecho que travava. A
  troca de senha tinha o mesmo desenho.
- **Correção:** login sem transação externa (cada consulta com a sua, curta; BCrypt sem
  conexão presa). Troca de senha com as conferências e o BCrypt fora de transação e uma
  transação curta só para gravar, que relê o usuário e recusa se a senha mudou no meio.
- **Depois:** com o mesmo ataque, o usuário legítimo foi atendido em 105 ms e o atacante
  recebeu 401/429 em até 0,4 s; a carga de login passou do colapso para ~200 logins/s sem
  erros (o limite agora é a CPU do BCrypt). `LoginSimultaneoTest` reproduz o problema com
  30 logins e 30 trocas simultâneas.

### 2. A fidelidade escapava pela troca de plano (lógica de negócio)
- **Comprovado:** um aluno no anual com 20% de multa (sair custaria R$ 219,78) trocava para
  um mensal com fidelidade, ou para um semestral com multa de 0,01%, e cancelava em
  seguida pagando R$ 0,00 ou R$ 0,05. A recepção conseguia fazer isso sozinha.
- **Causa:** a regra do ADR 0018 só exigia que o novo plano tivesse fidelidade e valor
  mensal igual ou maior; não comparava o compromisso que restava.
- **Regra nova (decidida pelo dono do projeto):** a troca não tem multa só se a multa que a
  nova matrícula cobraria, se o aluno saísse logo depois da troca, for pelo menos a que a
  atual cobraria hoje. Sem fidelidade, a nova cobraria zero. Nos outros casos, a multa da
  atual é aplicada, como num cancelamento.
- **Depois:** os dois casos comprovados pagam R$ 219,78; a troca legítima (anual por anual
  equivalente) continua sem multa.

### 3. Não havia backup (operação)
- Os dados existiam só no volume do Postgres.
- **Correção:** serviço `backup` no Compose: `pg_dump` diário a partir das 3h numa pasta do
  servidor (fora do Git), com 7 diários e 4 semanais; se o servidor estava desligado no
  horário, faz assim que voltar; cada arquivo só vale depois de o `pg_restore` conseguir
  lê-lo. `scripts/restaurar-backup.sh` salva o estado atual antes, restaura numa transação
  só e sobe a aplicação. Decisões do dono: pasta no servidor (ele copia para fora) e
  retenção de 7 diários + 4 semanais.
- **Usa o dono do banco**, e não o usuário da aplicação: o backup precisa do histórico das
  migrations, que o usuário da aplicação não lê; sem ele, o banco restaurado não sobe. O
  container não abre porta nenhuma.
- **O teste de restauração pegou um defeito** na primeira versão do script: o backup de
  segurança gravava por cima do arquivo diário que ia ser restaurado. O e2e passou a
  testar backup e restauração numa instalação do zero.

## Verificado e sem problema

Matriz de permissões (15 rotas × 6 tipos de acesso, inclusive token de senha temporária),
sem IDOR, campos extras ignorados, erros sem detalhes internos, `Cache-Control: no-store`
nas respostas com dados pessoais, CORS fechado; dependências Java e imagem da aplicação
sem CVEs altas ou críticas; nenhum segredo no histórico do Git; PIT no domínio todo sem
mutantes sobreviventes. Rotas com índice sustentaram ~7.400 req/s e matrículas novas ~760/s
na carga com 50 mil alunos.

## Pendências da revisão (não corrigidas aqui)

Registradas para correção futura, em ordem de prioridade: limite de requisições nas rotas
autenticadas; pool de conexões e tempos máximos de espera e de consulta; trilha de
auditoria fora das matrículas (etapa 8); validação de datas extremas e limite de
antecedência da matrícula; limites de memória e CPU para todos os containers; busca de
alunos sem índice (aceitável para o tamanho de uma academia); itens de arquitetura (tamanho
da `Matricula`, regras duplicadas entre plano e matrícula, relógio único, limpeza dos
testes).

## Consequências

- O login não depende mais de duas conexões por requisição; qualquer novo uso de
  `REQUIRES_NEW` dentro de outra transação precisa considerar o tamanho do pool.
- A academia passa a ter backup diário e um procedimento de restauração testado; a cópia
  para fora do servidor depende do dono.
