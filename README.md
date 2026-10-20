# Academia Gestão

[![CI](https://github.com/ryan-victor-dev/academia-gestao/actions/workflows/ci.yml/badge.svg)](https://github.com/ryan-victor-dev/academia-gestao/actions/workflows/ci.yml)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)

Sistema de gestão para academia: alunos, planos, matrículas, cobrança de
mensalidades, fichas de treino e controle de acesso por perfil.

Nasceu de um projeto real para uma academia e é desenvolvido em etapas, com
foco em código de produção: regras de negócio testadas, schema versionado,
build reproduzível e integração contínua.

## Stack

| Camada | Tecnologia |
|---|---|
| Linguagem / framework | Java 25, Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation, Actuator) |
| Autenticação | JWT (HS256) com Spring Security OAuth2 Resource Server (Nimbus), senhas em BCrypt |
| Banco de dados | PostgreSQL 17, migrations com Flyway |
| Testes | JUnit 5, AssertJ, Testcontainers, ArchUnit (fronteiras entre módulos), PIT (mutação) |
| Build / execução | Maven Wrapper, Docker (multi-stage), Docker Compose |
| Entrada / HTTPS | Caddy (certificado automático do Let's Encrypt) |
| CI | GitHub Actions (testes, build da imagem e envio das dependências ao GitHub) + Dependabot |

## Arquitetura

Monolito modular: um único deploy, com o código organizado **por domínio**, não
por camada.

```
br.com.academiagestao
├── alunos         cadastro de alunos
├── planos         planos e valores
├── matriculas     vínculo aluno ↔ plano
├── cobranca       mensalidades e pagamentos
├── treinos        fichas de treino
├── auth           autenticação e perfis
├── auditoria      registro de quem fez o quê
└── compartilhado  o que é comum a todos os módulos
```

A regra mais sensível — o ciclo de vida da mensalidade — é uma máquina de
estados em Java puro, sem dependência de framework ou banco:

```mermaid
stateDiagram-v2
    [*] --> PENDENTE
    PENDENTE --> PAGA
    PENDENTE --> VENCIDA
    PENDENTE --> CANCELADA
    VENCIDA --> PAGA
    VENCIDA --> CANCELADA
    PAGA --> ESTORNADA
    ESTORNADA --> [*]
    CANCELADA --> [*]
```

Qualquer transição fora do diagrama lança `TransicaoInvalidaException`.

As decisões de arquitetura e seus motivos estão registradas em
[`docs/adr/`](docs/adr):

| ADR | Decisão |
|---|---|
| [0001](docs/adr/0001-registrar-decisoes-com-adrs.md) | Registrar decisões com ADRs |
| [0002](docs/adr/0002-monolito-modular-por-dominio.md) | Monolito modular com pacotes por domínio |
| [0003](docs/adr/0003-postgresql-e-flyway.md) | PostgreSQL com schema versionado pelo Flyway |
| [0004](docs/adr/0004-dominio-em-java-puro.md) | Regras de negócio em Java puro, separadas da persistência |
| [0005](docs/adr/0005-testes-com-testcontainers.md) | Testes de integração com Testcontainers |
| [0006](docs/adr/0006-empacotamento-em-docker.md) | Empacotamento em Docker e porta 8090 |
| [0007](docs/adr/0007-autenticacao-com-jwt.md) | Autenticação stateless com JWT (HS256) e perfis |
| [0008](docs/adr/0008-cadastro-de-alunos.md) | Cadastro de alunos: CPF, inativação e acesso por perfil |
| [0009](docs/adr/0009-login-do-aluno-e-edicao-simultanea.md) | Login do aluno, senha temporária, edição simultânea e visão do instrutor |
| [0010](docs/adr/0010-revogacao-de-tokens-e-redefinicao-de-senha.md) | Revogação imediata de tokens e redefinição de senha |
| [0011](docs/adr/0011-gestao-da-equipe-e-recuperacao-de-admin.md) | Gestão da equipe e recuperação de acesso de ADMIN |
| [0012](docs/adr/0012-endurecimento-de-seguranca.md) | Endurecimento de segurança (revisão de 2026-10-16) |
| [0013](docs/adr/0013-planos.md) | Planos: preço, nome único e quem vê o quê |
| [0014](docs/adr/0014-segunda-revisao-de-seguranca.md) | Segunda revisão de segurança (2026-10-17) |
| [0015](docs/adr/0015-testes-de-arquitetura-e-mutacao.md) | Testes de arquitetura (ArchUnit) e de mutação (PIT) |
| [0016](docs/adr/0016-matriculas.md) | Matrículas: preço congelado, períodos sem sobreposição e quem vê o quê |
| [0017](docs/adr/0017-matriculas-decisoes-da-implementacao.md) | Matrículas: decisões tomadas durante a implementação |
| [0018](docs/adr/0018-fidelidade-trancamento-troca-e-vencimento.md) | Fidelidade, forma de pagamento, trancamento, troca de plano e vencimento |
| [0019](docs/adr/0019-fidelidade-trancamento-e-troca-decisoes-da-implementacao.md) | Fidelidade, trancamento e troca: decisões tomadas durante a implementação |
| [0020](docs/adr/0020-terceira-revisao-login-troca-e-backup.md) | Terceira revisão (2026-10-17): login sob carga, troca de plano e backup |
| [0021](docs/adr/0021-pendencias-medias-da-terceira-revisao.md) | Pendências médias da terceira revisão: limites, tempos, índices, datas e auditoria |
| [0022](docs/adr/0022-bloqueio-por-conta-e-ip-e-retencao-da-auditoria.md) | Bloqueio de login por conta e IP, e retenção da trilha de auditoria |
| [0023](docs/adr/0023-relogio-unico-e-limpeza-central-dos-testes.md) | Relógio único (datas testáveis, fuso da academia) e limpeza central dos testes |
| [0024](docs/adr/0024-cobranca.md) | Cobrança: mensalidades, atraso, pagamento e inadimplência |

## Como rodar

Pré-requisito: Docker com o plugin Compose. Java e Maven **não** são necessários
para subir o sistema.

```bash
git clone https://github.com/ryan-victor-dev/academia-gestao.git
cd academia-gestao
cp .env.example .env   # e preencha (veja abaixo)
docker compose up --build
```

A entrada é o **Caddy**, com HTTPS: `https://localhost` (ou
`https://localhost:<HTTPS_PORTA>`). O HTTP só redireciona para o HTTPS. Sem
domínio, o certificado é de desenvolvimento e o navegador avisa que não confia
nele. Com `DOMINIO` apontando para o servidor e as portas 80 e 443 abertas, o
Caddy obtém e renova sozinho um certificado do Let's Encrypt.

A aplicação (porta 8090) e o banco (5432) só aceitam conexões da própria
máquina, nunca da rede. As migrations do Flyway são aplicadas automaticamente
na inicialização.

### Configurando o `.env`

O `.env` guarda os segredos locais e **não vai para o Git** (o modelo
versionado é o `.env.example`). O Docker Compose lê o arquivo sozinho.

| Variável | Para que serve |
|---|---|
| `POSTGRES_PASSWORD` | Senha do dono do banco, usado só pelas migrations. **Obrigatória** |
| `DB_APP_SENHA` | Senha do usuário da aplicação no banco (`academia_app`), que só lê e grava dados. Mínimo de 16 caracteres. **Obrigatória** |
| `ADMIN_NOME`, `ADMIN_EMAIL` | Dados do primeiro ADMIN |
| `ADMIN_SENHA` | Senha de primeiro acesso do ADMIN, mínimo de 12 caracteres. O sistema exige trocá-la no primeiro login |
| `JWT_SEGREDO` | Chave que assina os tokens: Base64, mínimo de 32 bytes. **Obrigatória** |
| `JWT_EXPIRACAO` | Validade do token (padrão `15m`) |
| `DOMINIO` | Domínio do sistema, para o HTTPS de verdade. Vazio = `localhost` |
| `HTTP_PORTA`, `HTTPS_PORTA` | Portas públicas do Caddy (padrão 80 e 443; o Let's Encrypt exige essas duas) |
| `APP_MEMORIA`, `APP_CPUS`, `POSTGRES_MEMORIA`, `POSTGRES_CPUS` | Teto de memória e de núcleos da aplicação e do banco (padrão 1 GiB e 2 núcleos cada); o Caddy e o backup têm tetos fixos |

Para gerar valores fortes:

```bash
openssl rand -base64 24   # POSTGRES_PASSWORD, DB_APP_SENHA e ADMIN_SENHA (um para cada)
openssl rand -base64 32   # JWT_SEGREDO
```

> **Já tinha subido o sistema antes de existir `POSTGRES_PASSWORD`?** O Postgres
> só usa essa variável ao criar o banco pela primeira vez. Depois de preencher o
> `.env`, troque a senha do banco existente uma vez:
>
> ```bash
> set -a; . ./.env; set +a
> echo "ALTER ROLE academia PASSWORD :'senha'" | \
>   docker exec -i academia-postgres psql -U academia -d academia -v senha="$POSTGRES_PASSWORD"
> docker compose up -d
> ```

Na primeira subida, se o banco não tiver nenhum ADMIN, ele é criado e o log
mostra `Primeiro ADMIN criado: <e-mail>`. Nas próximas, nada acontece:
mudar `ADMIN_SENHA` depois disso não altera a senha já gravada.

### Usando a API

```bash
# Na própria máquina (porta interna 8090). De fora, use https://<DOMINIO>.
# Login: devolve {"token": "...", "tipo": "Bearer", "expiraEm": "..."}
curl -X POST http://localhost:8090/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email": "admin@academia.local", "senha": "<ADMIN_SENHA>"}'

# Demais chamadas: token no cabeçalho Authorization
curl http://localhost:8090/api/me -H "Authorization: Bearer <token>"
```

| Rota | Acesso |
|---|---|
| `POST /api/auth/login` | Público |
| `POST /api/auth/trocar-senha` | Qualquer usuário logado (inclusive com senha temporária) |
| `GET /actuator/health` | Público |
| `GET /api/me` | Qualquer perfil logado |
| `GET /api/usuarios` | ADMIN |
| `POST /api/usuarios` (ADMIN, RECEPCAO ou INSTRUTOR, com senha temporária) | ADMIN |
| `PUT /api/usuarios/{id}` (nome e perfil; mudar o perfil derruba as sessões da pessoa) | ADMIN |
| `POST /api/usuarios/{id}/desativar`, `/reativar` | ADMIN |
| `POST /api/usuarios/{id}/nova-senha` (senha temporária; nunca para um ADMIN) | ADMIN |
| `GET /api/alunos?busca=&incluirInativos=&pagina=&tamanho=` | ADMIN, RECEPCAO, INSTRUTOR¹ |
| `GET /api/alunos/{id}` | ADMIN, RECEPCAO, INSTRUTOR¹ |
| `POST /api/alunos` | ADMIN, RECEPCAO |
| `PUT /api/alunos/{id}` (exige `If-Match`²) | ADMIN, RECEPCAO |
| `POST /api/alunos/{id}/inativar`, `/reativar` (levam o login junto; inativar com matrícula vigente ou agendada dá 409) | ADMIN, RECEPCAO |
| `POST /api/alunos/{id}/login` (cria o login com senha temporária) | ADMIN, RECEPCAO |
| `POST /api/alunos/{id}/login/nova-senha` ("esqueci minha senha") | ADMIN, RECEPCAO |
| `GET /api/alunos/me` | ALUNO (o próprio cadastro) |
| `GET /api/planos?incluirInativos=`, `GET /api/planos/{id}` | Todos os perfis³ |
| `POST /api/planos`, `PUT /api/planos/{id}` (exige `If-Match`²; `fidelidade`, `percentualMulta` e `formaPagamento` opcionais⁵) | ADMIN |
| `POST /api/planos/{id}/inativar`, `/reativar` | ADMIN |
| `POST /api/matriculas` (`alunoId`, `planoId`, `inicio` opcional; copia nome, valor e duração do plano⁴) | ADMIN, RECEPCAO |
| `GET /api/matriculas/{id}`, `GET /api/matriculas?alunoId=` (histórico) | ADMIN, RECEPCAO |
| `POST /api/matriculas/{id}/cancelar` (`motivo` obrigatório; com fidelidade, grava a multa⁵) | ADMIN, RECEPCAO |
| `POST /api/matriculas/{id}/trancar` (`voltaPrevista` e `motivo`; `inicio` opcional) e `/registrar-volta` | ADMIN, RECEPCAO |
| `POST /api/matriculas/{id}/trocar-plano` (`planoId`; devolve a matrícula nova) | ADMIN, RECEPCAO |
| `GET /api/matriculas/vigente?alunoId=` (só se o aluno pode treinar hoje) | ADMIN, RECEPCAO, INSTRUTOR |
| `GET /api/matriculas/me` | ALUNO (as próprias) |
| `GET /api/auditoria?entidade=&entidadeId=&pagina=&tamanho=` (quem alterou planos, alunos e usuários, e o valor anterior⁶) | ADMIN |

¹ O instrutor recebe só nome, data de nascimento, observações e se está ativo,
e busca apenas por nome.
² O `GET` devolve `ETag: "<versão>"`; o `PUT` precisa mandar
`If-Match: "<versão>"`, para nenhuma edição apagar outra feita no meio tempo.
³ O aluno vê só os planos ativos, sem `ativo` e `criadoEm`. Reajustar o valor de
um plano vale para matrículas novas.
⁴ A matrícula começa hoje ou numa data futura e vai até início + N meses − 1 dia
(17/10 a 16/11 no plano mensal), mais os dias trancados. Os períodos de um aluno não
se sobrepõem, mas dá para agendar a renovação a partir do dia seguinte ao fim. A
situação (`AGENDADA`, `VIGENTE`, `TRANCADA`, `ENCERRADA`, `CANCELADA`, `TROCADA`) sai
das datas, no fuso de São Paulo. Trancamento: de 7 a 90 dias (90 somados), estende o
fim; a volta antecipada conta só os dias parados. Troca: a atual termina na véspera e
a nova começa hoje. O início pode ser agendado para até 1 ano à frente.
⁵ O dono define em cada plano se há fidelidade (com percentual de multa de 0,01% a
100%) e se o pagamento é mensal ou à vista; a matrícula copia essas regras. Sair antes
do fim de uma matrícula com fidelidade gera multa = percentual × meses inteiros que
faltam × valor mensal; a troca não gera multa só se a multa que o plano novo cobraria
(saindo logo depois da troca) for pelo menos a que a matrícula atual cobraria hoje.
⁶ `entidade` é `PLANO`, `ALUNO` ou `USUARIO`. Cada registro traz quem fez (`autor`, o id
do usuário; vazio quando foi o próprio sistema, como a recuperação de ADMIN pela linha
de comando), quando, a ação e só os campos que mudaram (`antes` e `depois`), nunca
senhas. A trilha é gravada na mesma transação da alteração, e o usuário da aplicação no
banco não consegue apagá-la nem reescrevê-la. Registros com mais de 5 anos são apagados
todo dia (ADR 0022).

Quem entra com senha temporária (login recém-criado ou senha redefinida) só
consegue trocar a senha (`POST /api/auth/trocar-senha`) e consultar `/api/me`.

Na gestão da equipe, ninguém desativa a si mesmo nem muda o próprio perfil, e o
sistema nunca deixa a academia sem pelo menos um ADMIN ativo.

Desativar, reativar, mudar o perfil, trocar ou redefinir a senha de um usuário
invalida na hora todos os tokens que ele já tinha: é preciso entrar de novo.

Erros saem em `application/problem+json` (RFC 9457): 400 para dados inválidos
(com a lista de campos em `erros`), 401 para token ausente, inválido ou
expirado, e para login recusado, 403 para perfil sem permissão (ou senha
temporária ainda não trocada), 404 para recurso inexistente, 409 para conflito
(ex.: CPF já cadastrado, período de matrícula ocupado), 412 quando o aluno foi alterado por outra pessoa
desde o `GET`, 428 quando falta o `If-Match`, 413 para corpo acima de 64 KB,
429 para excesso de tentativas de login (20 por minuto por IP; 10 senhas erradas
em 15 minutos bloqueiam o e-mail para aquele IP, e 100 somando todos os IPs o bloqueiam
para todos) ou de requisições (300 por minuto por usuário
logado) e 503 quando o banco está sobrecarregado (nenhuma conexão livre em 3 s ou
consulta acima de 5 s); 429 e 503 trazem o cabeçalho `Retry-After`.

### Backup e restauração

O serviço `backup` do Compose faz um `pg_dump` do banco todo dia, a partir das 3h
(`BACKUP_HORA`), na pasta `BACKUP_PASTA` (padrão `./backups`, fora do Git). Guarda 7
diários (`backups/diario`) e 4 semanais (`backups/semanal`). Se o servidor estiver
desligado no horário, o backup é feito assim que ele voltar. Cada arquivo é conferido
antes de valer, e o log mostra `Backup ok` ou `BACKUP FALHOU`
(`docker logs academia-backup`).

> **Copie a pasta de backups para fora do servidor** (HD externo, outro computador,
> nuvem). Contra disco queimado ou servidor perdido, só uma cópia em outro lugar salva.

```bash
docker compose run --rm backup agora                                      # backup na hora
scripts/restaurar-backup.sh backups/diario/academia-AAAA-MM-DD.dump --sim  # restaurar
```

Restaurar **apaga os dados atuais**: o script salva antes o estado atual em
`backups/antes-de-restaurar-*.dump` (para desfazer), para a aplicação, restaura numa
transação só (se falhar, nada muda) e sobe a aplicação de novo. O `scripts/e2e.sh`
testa o backup e a restauração numa instalação do zero.

### Recuperar o acesso de um ADMIN

Se um ADMIN esqueceu a senha, quem tem acesso ao servidor roda, na pasta do
projeto e com o sistema no ar:

```bash
docker compose run --rm app --recuperar-admin=admin@academia.local
```

O comando mostra uma senha temporária **só no terminal**, encerra e não deixa
container para trás. No primeiro acesso, o sistema exige uma senha nova. As
sessões abertas desse ADMIN caem na hora, e um login inativo é reativado.
E-mail inexistente ou de quem não é ADMIN termina com erro (código de saída 1)
sem mudar nada. Para os demais usuários, use as rotas de nova senha da API.

### Desenvolvimento local (fora do container)

Requer JDK 25. Sobe só o banco no Docker e roda a aplicação pelo Maven Wrapper:

```bash
docker compose up -d postgres
set -a; . ./.env; set +a   # carrega JWT_SEGREDO e ADMIN_* no shell
./mvnw spring-boot:run
```

### Testes

Requer Docker ligado (o Testcontainers sobe um PostgreSQL descartável):

```bash
./mvnw verify
```

O `verify` também confere as fronteiras entre os módulos (`ArquiteturaTest`), inclusive
que ninguém lê a hora do sistema fora do relógio único (ADR 0023). Nos testes, o
`RelogioDeTeste` para, adianta ou fixa esse relógio, e `LimpezaDoBanco.apagarTudo()` apaga
todas as tabelas na ordem das chaves estrangeiras.
Para checar se os testes de domínio realmente pegam erros (teste de mutação):

```bash
./mvnw -P mutacao test-compile org.pitest:pitest-maven:mutationCoverage
# relatório em target/pit-reports/index.html
```

Teste de ponta a ponta numa instalação do zero, isolada do seu ambiente (outras portas
e containers; tudo é apagado no final):

```bash
scripts/e2e.sh
```

`scripts/usuario-validacao.sh PERFIL` cria um usuário de teste direto no banco e devolve
um token dele. É só para desenvolvimento: ele se recusa a rodar se o `.env` tiver um
`DOMINIO` configurado.

> Nenhuma senha real fica no repositório: tudo vem do `.env`. O banco só aceita
> conexões desta máquina (`127.0.0.1`), nunca da rede. Os testes usam valores
> próprios, em `src/test/resources/config/application.yaml`, e não precisam do `.env`.

## Roadmap

- [x] **1. Fundação** — projeto, Postgres no Docker, Flyway, máquina de estados da mensalidade, Dockerfile, CI
- [x] **2. Autenticação** — login com JWT, senhas em BCrypt, perfis (ADMIN, RECEPCAO, INSTRUTOR, ALUNO) e autorização por perfil
- [x] **3. Alunos** — cadastro com validação de CPF, busca paginada, inativação e acesso por perfil
- [x] **4. Planos** — preço com duas casas, nome único entre os ativos, só o ADMIN altera, aluno vê os ativos
- [x] **5. Matrículas** — preço e regras do plano congelados na matrícula, períodos sem sobreposição garantidos pelo banco, fidelidade com multa, trancamento, troca de plano, visões do instrutor e do aluno
- [ ] **6–8.** Cobrança, treinos e auditoria (a trilha de planos, alunos e usuários já existe: ADR 0021)

## Autor

Ryan Victor — [GitHub](https://github.com/ryan-victor-dev)
