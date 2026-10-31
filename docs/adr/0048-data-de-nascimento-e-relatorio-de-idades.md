# ADR 0048 — Data de nascimento, idade e o relatório de idades e gênero

- **Status:** Aceito
- **Data:** 2026-10-30

## Contexto

Em 30/10, depois das funções do ADR 0047, o dono pediu para saber a data de nascimento e a idade de todos, "no
mesmo esquema do gênero". O aluno já tinha a data no cadastro desde o começo (V1), opcional, e toda a equipe a
via (o instrutor usa a idade para montar o treino). A equipe não tinha data nenhuma.

## Decisões do dono

1. **Quem vê:** a data do aluno continua visível para toda a equipe. A da equipe, como o gênero (ADR 0047,
   decisão 2): só o ADMIN e a própria pessoa.
2. **Obrigatória nos cadastros novos**, de alunos e da equipe. Quem já existe fica sem até alguém preencher,
   como o CPF da equipe (ADR 0047, decisão 1).
3. **Onde aparece a idade:** nas listas (alunos e equipe), no cadastro de cada um e em "Meus dados".
4. **Relatório de idades e gênero** de todos os alunos e da equipe: **só o ADMIN** vê.

## Regras comuns

- A data não pode estar no futuro nem ser de mais de 120 anos atrás (a mesma regra que o aluno já tinha,
  revisão de 17/10), contada no fuso da academia. A regra mora em `compartilhado.Nascimento`, usada pelos dois
  cadastros (o `auth` não depende do módulo de alunos).
- A idade é em anos completos. Quem nasceu em 29/02 completa os anos em 01/03 nos anos comuns (Código Civil,
  art. 132, § 3º: falta o dia correspondente, vale o seguinte), igual nas telas e no servidor.
- A data se corrige, mas não se apaga: sem data no pedido de edição, a atual fica.

## O que mudou, parte a parte

### Data de nascimento da equipe
Coluna nova em `usuarios` (V62), que aceita nulo: a obrigação fica na aplicação, como o CPF (V55), para quem é de
antes continuar editável. O banco barra a data em login de aluno (a do aluno fica no cadastro dele) e a anterior
a 1900. `POST /api/usuarios` exige `dataNascimento`; `PUT /api/usuarios/{id}` aceita sem ela.

A própria pessoa da equipe informa ou corrige a sua em "Meus dados" (`PUT /api/me/nascimento`), como faz com o
gênero; o aluno, não (a data dele é do cadastro, e quem corrige é a recepção). A data vai para a trilha, como o
CPF: ao contrário da orientação sexual, não é dado sensível pela LGPD. Só o ADMIN (`/api/usuarios`, que já era
só dele) e a própria pessoa (`/api/me`) a recebem.

Na aba Equipe, a linha mostra a idade, ou "Sem nascimento" para quem é de antes; o campo aparece no cadastro
(obrigatório) e na edição (obrigatório só para quem já tem).

### Data de nascimento do aluno obrigatória no cadastro novo
`POST /api/alunos` sem a data dá 400 ("data de nascimento é obrigatória"). Na edição (`PUT`, que substitui o
cadastro inteiro), quem tem a data precisa mandar uma: mandar sem dá o mesmo 400, e nada muda. Quem foi cadastrado
antes continua editável sem ela. A regra fica na entidade (`Aluno.novo` e `atualizar`), e não na validação do
pedido, porque depende do cadastro atual. O banco não muda: a coluna continua aceitando nulo por causa dos antigos.

Nas telas, o campo "Nascimento" é obrigatório no cadastro novo e na edição de quem tem a data, e o botão só se
habilita com ela. A idade aparece ao lado da data na lista de alunos, no cadastro e em "Meus dados" do aluno
("10/05/1990 (35 anos)"): tanto no quadro da página inicial quanto na página "Meus dados" do menu, onde o aluno só
consulta (quem corrige é a recepção). A data continua visível a toda a equipe, como antes (decisão 1).

### Relatório de idades e gênero (só o ADMIN)
`GET /api/relatorios/idades-e-genero` (página "Idades e gênero" no menu do ADMIN): para alunos e para a equipe,
separados, quantos há em cada faixa etária (até 17, 18 a 29, 30 a 39, 40 a 49, 50 a 59, 60 ou mais, e sem data), em
cada identidade de gênero e em cada orientação sexual, com quem não respondeu à parte e a porcentagem; e a lista de
todos, com a idade e o que cada um respondeu ("outra" mostra o que a pessoa escreveu). Por padrão só quem está ativo;
`inativos=true` inclui alunos inativos e equipe desativada. As faixas foram escolhidas na implementação (o dono pode
pedir outras).

O relatório fica no módulo de alunos e lê a equipe por uma porta nova do `auth` (`PerfisDaEquipe`), que só ele usa.
Traz a orientação sexual de todos, dado sensível: a resposta não vai para cache, e cada abertura vai para o registro
de acessos (ADR 0031) numa linha só, com um tipo novo (`RELATORIO_PERFIL`, V63), e não uma linha por pessoa listada,
que é o que o registro evita nas listas. A tela da Auditoria mostra e filtra esse tipo.

A lista vem inteira, sem paginação: numa academia são centenas ou poucos milhares de pessoas, e o relatório é aberto
de vez em quando, pelo ADMIN. Se um dia passar disso, a lista ganha paginação e as contagens continuam inteiras.
