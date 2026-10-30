# ADR 0047 — Novas funções pedidas pelo dono (equipe, cadastro, boleto, notícias e avisos)

- **Status:** Aceito
- **Data:** 2026-10-30

## Contexto

Em 30/10 o dono pediu sete funções novas: CPF no cadastro da equipe; identidade de gênero e orientação
sexual de alunos e equipe; esconder da aba Equipe quem foi desativado; boleto gerado no balcão; o
instrutor excluir exercício cadastrado por engano; uma página de notícias, comunicados e eventos com
fotos, vídeos, links e documentos, como página inicial; e avisos por e-mail e SMS das faturas (antes de
vencer e em atraso) e das notícias e eventos. Ele decidiu as regras de negócio abaixo antes de começar.
Cada função entra numa parte (um commit), e a seção dela registra o que mudou no sistema.

## Decisões do dono

1. **CPF da equipe:** obrigatório em todo cadastro novo, sem repetir. Os antigos ficam sem CPF até o
   ADMIN preencher; a tela avisa.
2. **Identidade de gênero e orientação sexual:** opcionais, com "prefiro não informar" entre as opções.
   Orientação sexual é dado pessoal sensível (LGPD, art. 5º, II): **só o ADMIN e a própria pessoa veem**;
   recepção e instrutor, não. A tela usa "identidade de gênero" e "orientação sexual" (e não "opção
   sexual"), com as identidades e orientações usadas hoje e um campo "outra" para a pessoa descrever.
3. **Equipe desativada:** sai da lista principal da aba Equipe e aparece só no filtro "inativos", na
   mesma aba.
4. **Boleto no balcão:** a recepção e o ADMIN geram o boleto da cobrança para o aluno, pelo mesmo
   pagamento online do ADR 0034 (com o provedor desligado, não há boleto, como hoje no app do aluno).
5. **Instrutor exclui exercício:** qualquer exercício do catálogo **nunca usado** em ficha, pela mesma
   regra do ADR 0043 (quem barra é a chave estrangeira; com histórico, 409 e a sugestão de inativar).
6. **Notícias e eventos:** ADMIN e recepção publicam notícias, comunicados e eventos; **só quem tem login**
   vê (aluno e equipe), e é a página inicial depois de entrar. Anexos guardados no servidor e no backup:
   fotos até 5 MB, documentos (PDF) até 10 MB, vídeos curtos até 50 MB; vídeo longo vai por link.
7. **Avisos de fatura:** por e-mail e SMS, uma vez por dia às **9h** no horário da academia: 5, 3 e 1 dia
   antes do vencimento, no dia, e com 1, 3 e 5 dias de atraso. Antes de cada envio, o sistema confere de
   novo que a cobrança continua em aberto: **quem pagou não recebe aviso de atraso**. Os de atraso dizem
   que o atraso aumenta o valor (multa e juros) e bloqueia o acesso; os de antes do vencimento dizem o
   mesmo em tom amigável, como quem adianta uma coisa boa. O aviso de fatura **não se desliga** (é do
   contrato).
8. **Avisos de notícias e eventos:** por e-mail e SMS, e **a pessoa pode desligar** no app.
9. **SMS:** construído com um envio simulado, atrás de uma porta, como o pagamento online (ADR 0034); a
   empresa (Zenvia, Twilio, AWS SNS…) é escolhida antes de ir ao ar. Sem empresa, o SMS fica desligado.

Ficaram de fora: página de notícias aberta na internet (fotos e documentos ficariam públicos e
indexados), gênero obrigatório, toda a equipe ver o gênero e a orientação, e vídeos de até 500 MB.

## O que mudou, parte a parte

### Equipe desativada só na aba dos inativos (decisão 3)
A tela Equipe tem duas abas: "Ativos", que abre primeiro, e "Membros da equipe inativos", onde ficam
quem foi desativado e o botão de reativar. A API não mudou (`GET /api/usuarios` traz a equipe inteira,
que é pequena), e a tela separa pela situação. Os logins de alunos continuam à parte, como antes.

### O instrutor exclui exercício nunca usado (decisão 5)
`DELETE /api/exercicios/{id}` passa a aceitar ADMIN e INSTRUTOR, e o catálogo mostra o botão Excluir aos
dois. A regra do ADR 0043 continua: só sai o exercício que nunca entrou numa ficha, mesmo encerrada (a
chave estrangeira barra, 409, e o caminho é inativar), com a trava contra a ficha montada no mesmo
instante e o registro `EXCLUIDO` na trilha. Aluno, plano e equipe continuam só com o ADMIN.

### CPF da equipe (decisão 1)
`usuarios` ganhou a coluna `cpf` (V55): só os 11 dígitos (`ck_usuarios_cpf`), nunca no login de aluno, cujo
CPF fica no cadastro dele (`ck_usuarios_cpf_so_da_equipe`), e sem repetir (`uq_usuarios_cpf`). A coluna
aceita nulo, e a obrigação fica na aplicação: um CHECK, mesmo `NOT VALID`, valeria também no UPDATE, e
renomear alguém de antes passaria a falhar.

- `POST /api/usuarios` exige o `cpf` (com ou sem pontuação, conferido pelos dígitos); repetido, 409 "CPF já
  cadastrado". `PUT /api/usuarios/{id}` aceita o `cpf` opcional: sem ele (ou em branco), o atual fica. O CPF
  se corrige, mas não se apaga.
- A resposta traz o `cpf` (só o ADMIN lista a equipe). A trilha registra o CPF, como já fazia com o do aluno.
- A tela pede o CPF no login novo; quem é de antes aparece com "Sem CPF" e ganha o seu pela edição.
- As classes `Cpf` e `CpfValido` saíram do módulo de alunos para o `compartilhado`: o `auth` não depende de
  outros módulos (ADR 0015).
- O primeiro ADMIN, criado a partir do `.env` na instalação (ADR 0011), continua nascendo sem CPF: o
  `.env` não tem o CPF de ninguém, e ele preenche o seu depois pela tela.

A checagem dá a mensagem clara; numa corrida, quem barra é o índice único, e só o erro dele vira o 409 do CPF.
O `CpfDaEquipeTest` força a corrida (outra transação grava o CPF sem confirmar; o pedido passa pela checagem
e espera no índice, conferido no `pg_stat_activity`). Mutantes equivalentes: tirar a checagem `existsByCpf`
do cadastro ou da edição não muda o que se vê, porque o índice dá o mesmo 409 (a checagem só evita a ida ao
banco).
