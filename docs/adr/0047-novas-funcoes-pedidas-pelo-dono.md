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

### Identidade de gênero e orientação sexual (decisão 2)
Duas tabelas (V56): `identidade_dos_alunos`, pelo cadastro (o aluno pode não ter login), e
`identidade_da_equipe`, pelo login. Ficam fora do cadastro para não sair junto com o aluno que a recepção e o
instrutor consultam, nem com o retrato da trilha. Rotas próprias, com `Cache-Control: no-store`:
`/api/alunos/me/identidade` (o aluno), `/api/me/identidade` (a equipe), e `/api/alunos/{id}/identidade` e
`/api/usuarios/{id}/identidade` (só o ADMIN). A recepção e o instrutor recebem 403; o cadastro do aluno, a
lista da equipe e o `/api/me` não trazem esses campos.

- **Opções:** identidade de gênero (mulher e homem cisgênero, mulher e homem trans, travesti, pessoa não binária,
  gênero fluido, agênero) e orientação sexual (heterossexual, lésbica, gay, bissexual, pansexual, assexual,
  demissexual, queer), cada uma com "outra" (a pessoa descreve, até 50 caracteres) e "prefiro não informar".
  Nulo é "não respondido", e responder nulo apaga.
- **Trilha:** registra `IDENTIDADE_ALTERADA`, com quem e quando, mas **sem os valores**: a trilha fica 5 anos
  (ADR 0022), e o dado sensível continuaria lá depois de a pessoa apagá-lo (o mesmo motivo do item 9 do ADR
  0046). O ADMIN abrir a identidade de um aluno vai para o registro de acessos (ADR 0031), como abrir o cadastro.
- **Quem preenche:** a própria pessoa, em "Meus dados", e o ADMIN, pelo que a pessoa informou no balcão. O aluno
  inativo (só de leitura, ADR 0029) consulta, mas não altera: pede ao ADMIN.
- **Banco:** a descrição só acompanha "outra" e é obrigatória nela (CHECK com `IS NOT DISTINCT FROM`, que não
  deixa passar a descrição solta com a opção nula). A aplicação não tem DELETE nessas tabelas; a linha sai com o
  cadastro (chave estrangeira em cascata, ADR 0043). A gravação trava o cadastro (`FOR SHARE`): uma exclusão no
  mesmo instante faz o pedido esperar e sair com 404, e não com 500 (o teste força essa corrida).

Mutantes equivalentes nas telas: mandar a descrição fora da opção "outra" (o campo nem existe na tela, e o
valor sai nulo do mesmo jeito).

### Boleto gerado no balcão (decisão 4)
Antes, só o aluno gerava o boleto, pelo app; no balcão, a recepção só registrava dinheiro, Pix ou cartão.
Agora a recepção e o ADMIN geram, na página do aluno, o boleto de uma cobrança em aberto
(`POST /api/pagamentos-online/balcao/boleto`), o mesmo do app (ADR 0034): o valor de hoje, o prazo do
vencimento (ou alguns dias, na vencida), devolve o que ainda vale e cancela na empresa o que venceu. Quem
gerou fica no pagamento (`gerado_por`). A tela mostra a linha digitável, o botão de copiar e o link do boleto
(quando a empresa manda um); o aluno também vê o boleto no app.

- O boleto não passa pelo caixa (V41): a cobrança fica paga quando a empresa avisa, como no app.
- O balcão gera para **qualquer** cobrança em aberto, como o recebimento no balcão; o limite de adiantar (só
  as vencidas ou a próxima) continua só no app. Decisão técnica: o balcão já recebe qualquer uma em dinheiro.
- Sem empresa, ou com o boleto desligado na configuração, o botão nem aparece (`GET
  /api/pagamentos-online/balcao`), e a API recusa (409).

### Notícias, comunicados e eventos como página inicial (decisão 6)
Módulo novo, `noticias`, sem porta pública por enquanto (ADR 0015). Tabelas `publicacoes` e `publicacao_links`
(V57). Cada publicação é uma notícia, um comunicado ou um evento, com título (até 150), texto (até 5.000) e até
10 links; só o evento tem quando (início obrigatório, fim opcional, não antes do início) e onde. Os anexos vêm
na parte seguinte.

- **Quem:** todos que têm login leem (`GET /api/publicacoes`, filtro por tipo, e `/proximos-eventos`); o ADMIN
  e a recepção publicam, editam e excluem. A página inicial de todos os perfis mostra o mural depois do
  "Olá"; a do aluno mantém antes o aviso de atraso e depois a matrícula.
- **Segurança:** o texto é texto simples, guardado como veio e mostrado como texto (o React escapa; nada de
  `innerHTML`). Os links só com `http://` ou `https://`, em minúsculas e com servidor, conferidos na aplicação e
  no banco (CHECK): um `javascript:` viraria código no navegador de quem clicasse. Abrem fora do app, com
  `rel="noopener noreferrer"`.
- **Trilha:** `PUBLICADA`, `EDITADA` e `EXCLUIDA`, com o retrato (o conteúdo é público, não é dado pessoal).
  A aplicação tem DELETE nas duas tabelas (exclusão e troca dos links), mas não TRUNCATE.
- **Hora do evento:** o campo da tela não tem fuso; a tela converte para a hora da academia, e não a do aparelho
  (testado com o aparelho em Tóquio).
- **Edição ao mesmo tempo:** a última vence (sem ETag, ao contrário dos cadastros do ADR 0009): é um texto de
  mural, editado por poucas pessoas, e a trilha guarda a versão anterior. Edição e exclusão juntas se esperam
  (trava na linha).

Mutantes equivalentes na tela: mandar data e local fora do evento, e filtrar os links vazios (os campos nem
existem fora do evento, e o link vazio é barrado pelo campo obrigatório).

### Anexos: fotos, vídeos e documentos (decisão 6)
**Onde ficam: no próprio banco**, em pedaços de até 1 MB (`publicacao_anexos` e `anexo_partes`, V58). A aplicação
roda com o disco só de leitura e o `/tmp` na memória (que conta no teto de 1 GB do container): um vídeo de 50 MB
não pode passar por ali. No banco, os anexos entram no backup cifrado que já existe (ADR 0041) e na restauração
que o e2e testa, sem volume nem script novos; saem junto com a publicação (cascata), sem arquivo órfão; e seguem
as permissões do usuário da aplicação. O custo é o banco e o backup crescerem com os anexos: por isso há um teto
de espaço total (`ANEXOS_ESPACO_TOTAL`, 5 GB por padrão) e a escolha de vídeo curto (o longo vai por link).

- **Envio:** `POST /api/publicacoes/{id}/anexos`, o arquivo cru no corpo e o nome em `X-Nome-Do-Arquivo`. O
  limite de corpo (64 KB na aplicação, 1 MB no Caddy) tem uma exceção só para essa rota (50 MB e 51 MB); o perfil
  é conferido antes de ler o corpo (`PerfilAntesDoCorpo`). O arquivo chega em pedaços de 1 MB, cada um gravado
  numa transação curta, com o anexo em `ENVIANDO`: um envio lento pela rede não segura uma conexão do banco
  (ADR 0020). No fim, numa transação que trava a publicação e reconta, vira `PRONTO`. Qualquer falha apaga o que
  chegou, e o envio interrompido (`ENVIANDO` há mais de 1 hora) é limpo a cada 15 minutos.
- **Tipo:** pelos primeiros bytes (JPEG, PNG, WebP, GIF, MP4, WebM, PDF), nunca pelo nome ou pelo tipo declarado.
  SVG e HTML ficam de fora (podem levar script). Tetos: foto 5 MB, PDF 10 MB, vídeo 50 MB; 10 por publicação; no
  máximo 3 envios ao mesmo tempo (503 acima disso: cada um usa 1 MB de memória). O espaço total é conferido a
  cada pedaço (envios simultâneos podem passar juntos do teto, no máximo pelo teto de envios ao mesmo tempo).
- **Leitura:** `<img>` e `<video>` não mandam o token. Cada publicação vem com o endereço de cada anexo
  **assinado** (HMAC-SHA256 com uma chave derivada do `JWT_SEGREDO`, sem configuração nova), válido até a virada
  da hora seguinte (1 a 2 horas; o endereço não muda dentro da hora, e o navegador aproveita o que baixou).
  Assinatura errada, prazo vencido ou anexo inexistente dão o mesmo 404. O tipo da resposta é o reconhecido; a
  foto e o vídeo abrem na página, o PDF vem como download, com `nosniff` e cache só do navegador. O conteúdo é
  lido em pedaços (1 MB por vez) e atende `Range` (o vídeo toca sem baixar tudo).
- **Trilha:** `ANEXO_ADICIONADO` e `ANEXO_EXCLUIDO` na publicação, com nome, tipo e tamanho.
- **Corrida:** a publicação excluída no meio de um envio leva o anexo (cascata), e o envio sai com 404, e não 500
  (o `AnexoLimitesTest` força a corrida com um envio parado no meio).

Ficou de fora: comprimir ou converter vídeo (pesado para o servidor; o celular já grava comprimido) e miniatura das
fotos (o navegador reduz na tela; com 5 MB por foto, o peso é aceitável para uma academia).
