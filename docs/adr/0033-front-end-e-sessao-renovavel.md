# ADR 0033 — Front-end (PWA em React) e sessão renovável

- **Status:** Aceito (atualiza os ADRs 0007 e 0010)
- **Data:** 2026-10-23

## Contexto

As etapas 1 a 8 entregaram só a API REST, sem nenhuma tela. Antes de começar o front-end, o
dono respondeu (2026-10-23):

1. **Quem usa:** a recepção, os professores (perfil INSTRUTOR) e os alunos, cada um com as
   permissões que a API já tem (o ADMIN, com tudo).
2. **Onde:** a recepção no computador; professores e alunos no celular. Um site responsivo
   instalável (PWA) serve: não precisa ser app de loja.
3. **Hospedagem:** junto da API, servido pelo mesmo Caddy.
4. **Tecnologia:** React + TypeScript + Vite.

Um problema aparece antes da primeira tela: o token vale 15 minutos e não se renova (ADR 0007).
A recepção teria de digitar a senha a cada 15 minutos, o dia inteiro, e o aluno, toda vez que
abrisse o app. Aumentar a validade do token não serve: um token de 30 dias, guardado no
navegador, é uma credencial longa ao alcance de qualquer script da página (XSS).

O dono decidiu quanto a sessão dura: **12 horas para a equipe** (um turno; o ADMIN também) e
**30 dias para o aluno**.

## Decisões

### Front-end

- Projeto em `web/` (React + TypeScript + Vite), construído dentro do Docker e servido pelo
  Caddy como arquivos estáticos. A API continua em `/api`, no mesmo endereço: sem CORS, e o
  cookie da sessão (abaixo) fica restrito ao próprio site.
- PWA: instalável na tela inicial do celular, sem loja.
- Cada perfil vê só o menu do que pode fazer. Esconder um botão é conveniência: quem protege
  continua sendo a API (`@PreAuthorize`), que responde 403 a qualquer tela forçada.
- O token de acesso fica **só na memória** da página (nunca em `localStorage`): fechar a aba
  apaga o token; a sessão (cookie) traz um novo.

Os detalhes de cada tela, a política de conteúdo (CSP) do site e os testes do front entram nas
próximas partes deste ADR conforme forem feitos.

### Sessão renovável

- O login continua devolvendo o token de acesso (15 min) no corpo e, além dele, abre uma
  **sessão**: uma linha em `sessoes` e um cookie com o segredo dela.
- O cookie é `__Secure-academia-sessao`: `HttpOnly` (script nenhum lê), `Secure`,
  `SameSite=Strict` e `Path=/api/auth` (só vai nas rotas de sessão, nunca no resto da API).
- O banco guarda só o **hash SHA-256** do segredo (32 bytes aleatórios): quem lê o banco (ou um
  backup) não consegue usar a sessão. SHA-256, e não BCrypt, porque o segredo é aleatório e
  longo: não há o que adivinhar, e a renovação não pode custar o tempo de um BCrypt.
- `POST /api/auth/renovar`: com o cookie, devolve um token novo (mesmo formato do login) e
  **troca o segredo** (rotação). O token novo sai do usuário como está no banco: mudar o perfil
  ou deixar o aluno só de leitura passa a valer em até 15 minutos, sem novo login.
- `POST /api/auth/sair`: encerra a sessão e apaga o cookie. Responde 204 mesmo sem cookie ou com
  sessão já encerrada (sair duas vezes não é erro).
- Nessas duas rotas, o cabeçalho `Authorization` é ignorado: quem renova costuma ter o token
  vencido, e o filtro recusaria a requisição (401) antes de a rota ler o cookie. Achado na
  validação no Docker.
- **Prazo fixo** contado do login: 12 horas para a equipe, 30 dias para o aluno (configuráveis
  em `academia.sessao`). Renovar não estica o prazo; o último token não passa do fim da sessão.
- **Revogação:** a sessão guarda a versão do token do usuário (ADR 0010). Desativar, reativar,
  trocar ou redefinir a senha aumentam a versão, e a sessão deixa de renovar. Para o aluno que
  perdeu o celular, a recepção redefine a senha dele (rota que já existe) e todas as sessões
  caem.
- **Segredo reaproveitado:** usar de novo um segredo já trocado é sinal de cópia (alguém levou o
  cookie). A sessão é encerrada, e os dois lados precisam entrar de novo. Exceção: até
  **30 segundos** depois da troca, o segredo anterior ainda devolve um token, sem trocar de
  novo. Duas abas renovando juntas mandam o mesmo segredo; sem essa folga, a segunda derrubaria
  a sessão. O cookie novo já está no navegador (ele é do site, não da aba).
- **Trocar a senha** (`/api/auth/trocar-senha`) abre uma sessão nova (a versão do token mudou, e
  a antiga para de renovar).
- Recusa (sem cookie, cookie inválido, sessão vencida, encerrada ou revogada): **401** com o
  mesmo corpo em todos os casos, e o cookie apagado.
- Sessões vencidas ou encerradas há mais de 1 dia são apagadas por uma tarefa agendada (a cada
  hora). A aplicação passa a ter DELETE em `sessoes`.

### CSRF

O ADR 0007 desligou o CSRF porque a API não usava cookie, e avisou que ele precisaria voltar se
o token fosse para um cookie. O token de acesso continua no cabeçalho `Authorization`. O cookie
existe só para `renovar` e `sair`, e:

- `SameSite=Strict`: o navegador não manda o cookie em nenhuma requisição que parta de outro
  site, nem em link nem em formulário.
- Mesmo se mandasse, a resposta de `renovar` (o token) não pode ser lida por outro site (sem
  CORS). Um `sair` forjado só desconectaria a vítima.

Por isso o CSRF continua desligado.

## Alternativas consideradas

- **Token de 12 h / 30 dias, sem sessão:** uma linha de código, mas o token longo ficaria ao
  alcance de scripts da página, e o perfil só mudaria no próximo login.
- **Token de acesso em `localStorage`:** sobrevive ao recarregar a página, mas qualquer XSS o
  lê. Com o cookie `HttpOnly`, recarregar custa uma chamada a `renovar`.
- **Sessão que se estende a cada uso (deslizante):** o aluno nunca mais digitaria a senha. O dono
  escolheu prazos fixos.
- **Sessão do Spring (`JSESSIONID`) para tudo:** exigiria CSRF em toda a API e mudaria a
  autenticação de todas as rotas e testes.
- **Rotação sem folga:** mais simples, mas duas abas abertas derrubariam a sessão.

## Consequências

- Mais uma consulta (e uma gravação) a cada 15 minutos por pessoa conectada: irrelevante perto
  da consulta por requisição do ADR 0010.
- Sair encerra a sessão, mas o token de acesso já emitido vale até expirar (até 15 min). O
  front-end o descarta na hora; para derrubar tudo de imediato continua existindo a troca de
  senha (ou a desativação).
- Chamadas sem navegador (scripts, `curl`) seguem como antes: o token vem no corpo do login, e o
  cookie pode ser ignorado.
