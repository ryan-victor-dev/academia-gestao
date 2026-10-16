# ADR 0006 — Empacotamento em Docker (multi-stage, não-root) e porta 8090

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O sistema vai rodar num servidor que já hospeda outros serviços (Nextcloud na
8080, Pi-hole na 8081). Preciso de um jeito reproduzível de construir e subir a
aplicação, sem instalar Java nem Maven no servidor.

## Decisão

- **Dockerfile multi-stage:** a etapa `build` usa o JDK e o Maven Wrapper para
  gerar o `.jar`; a etapa final usa só o **JRE** e recebe apenas o `.jar`.
- As dependências são baixadas numa camada separada (`dependency:go-offline`)
  antes de copiar `src/`, para o Docker reaproveitar o cache quando só o código
  muda.
- O processo roda com um **usuário sem privilégios** (`app`), não como root.
- `.dockerignore` evita mandar `target/`, `.git/` e afins para o build.
- A aplicação usa a porta **8090**, fora das portas já ocupadas no servidor.
- O `docker-compose.yml` sobe Postgres + app; a app só inicia depois que o
  healthcheck do Postgres passa (`condition: service_healthy`).
- O CI também constrói a imagem, então um Dockerfile quebrado falha no PR.

## Consequências

- Imagem final menor e com menos superfície de ataque (sem compilador, sem root).
- Subir o sistema em qualquer máquina com Docker é um `docker compose up`.
- As credenciais do banco no Compose e no `application.yaml` são de
  **desenvolvimento**. Antes de ir para o servidor da academia, elas saem do
  repositório e passam a vir de variáveis de ambiente / arquivo `.env` fora do Git.
