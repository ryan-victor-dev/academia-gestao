# ADR 0028 — Pendências médias dos treinos: memória, histórico imutável, texto e listas

- **Status:** Aceito (completa o ADR 0027)
- **Data:** 2026-10-22

## Contexto

A validação da etapa 7 (treinos), em 2026-10-21, achou cinco problemas médios,
reproduzidos no Docker e numa instalação de carga isolada:

1. **Sem memória, a aplicação ficava fora do ar para sempre.** Depois de um
   `OutOfMemoryError`, a JVM seguia viva e travada; o `restart` do Compose só age quando o
   processo morre, e ele não morria.
2. **A ficha encerrada só era imutável no código.** O gatilho da V30 olhava só a linha
   nova: dava para tirar uma divisão da encerrada, apagar as divisões e os exercícios dela
   e trocar o aluno de uma ficha. O usuário da aplicação tinha `DELETE` em tudo.
3. **Listas sem limite.** Com 60 mil revisões vencidas, 16 chamadas ao mesmo tempo
   esgotavam a memória (e, pelo item 1, a aplicação não voltava). Um histórico de 2000
   fichas tinha 27 MB.
4. **Byte nulo num texto dava 500** em todos os módulos (o PostgreSQL o recusa), com uma
   pilha no log a cada tentativa; no exercício, virava "nome em uso".
5. **Data de revisão sem teto:** 9999-12-31 era gravada; um ano maior dava 500.

## Decisões

### A JVM sai quando a memória acaba
- `-XX:+ExitOnOutOfMemoryError` no `Dockerfile`: o processo termina, e o `restart` o
  traz de volta em segundos. Vale para a aplicação inteira.

### O histórico de treinos se protege no banco (V32)
- O gatilho recusa: mudar a ficha encerrada; mudar o aluno, a montagem ou o autor de
  qualquer ficha; alterar divisões e exercícios (a correção apaga os da ficha atual e grava
  novos); acrescentar ou apagar divisões e exercícios de uma encerrada.
- O usuário da aplicação perde `DELETE` em `fichas` e `exercicios` (nada disso se apaga) e
  `UPDATE` nas divisões e nos exercícios da ficha.
- O dono do banco ainda apaga as divisões e os exercícios de uma encerrada: é a limpeza
  dos testes, que conecta como dono. A aplicação conecta como `academia_app`.

### Texto com caractere de controle é recusado na entrada
- De U+0000 a U+001F, só a quebra de linha, o retorno de carro e o tab passam. Os outros
  dão 400 "Dados inválidos", com o campo.
- Num lugar só para a API inteira: um desserializador de texto do Jackson para o JSON
  (o campo vem com o caminho, como `divisoes[1].exercicios[0].carga`) e um `@InitBinder`
  global para parâmetros e cabeçalhos (como o `?busca=` dos alunos, que também dava 500).

### Histórico e revisões vencidas em páginas
- `GET /api/fichas?alunoId=` e `/api/fichas/me`: 10 por página, até 20, porque cada ficha
  vem inteira. `GET /api/fichas/revisoes-vencidas`: 20 por página, até 100.
- As divisões e os exercícios carregam por `@BatchSize`, e não por `SUBSELECT`: o
  `SUBSELECT` repete a consulta original **sem o limite da página** e lia o histórico
  inteiro do aluno para montar cada página. O resultado era o mesmo; só o SQL mostrou.
- A lista de revisões vencidas deixa de fora os alunos inativos, e os treinos não leem a
  tabela de alunos (ADR 0002). Filtrar depois de paginar deixaria páginas pela metade.
  Então a ficha atual guarda uma cópia de "o aluno está ativo" (`fichas.aluno_ativo`,
  V33), e o módulo de alunos avisa os treinos ao inativar e ao reativar, na mesma
  transação, por uma interface que ele declara (`AoMudarASituacaoDoAluno`, como o
  `ImpedimentoParaInativar` do ADR 0016). A V33 marcou os que já estavam inativos.
- Os nomes da lista vêm por projeção (id e nome), sem carregar o cadastro de cada aluno.

### Revisão no máximo 1 ano à frente (decisão do dono)
- Como o início da matrícula (ADR 0021). O ano conta do dia em que a data é definida, no
  horário da academia: na montagem, o dia da montagem; na correção, o dia da correção.

## Alternativas descartadas
- **Healthcheck que reinicia a aplicação travada:** o Compose não reinicia um container
  só por estar "unhealthy"; seria preciso outro serviço vigiando. Sair no OOM resolve o
  caso visto, sem peças novas.
- **Desligar o gatilho na limpeza dos testes** (`session_replication_role`): desliga
  também as chaves estrangeiras na sessão, e testes em paralelo deixariam linhas órfãs.
- **Ler a tabela de alunos na consulta das revisões vencidas:** um `JOIN` simples, mas
  quebraria a regra do ADR 0002, que o resto do sistema segue.
- **Histórico só com o resumo** (sem divisões) e a ficha inteira por id: menos bytes, mas
  dois pedidos para ver uma ficha antiga, inclusive para o aluno. A página pequena já
  limita o tamanho.
- **Teto da revisão contado da montagem:** uma ficha atual com mais de um ano não poderia
  mais ganhar revisão.

## Consequências
- `GET /api/fichas?alunoId=`, `/me` e `/revisoes-vencidas` passam a devolver uma página
  (`conteudo`, `totalElementos`...), e não uma lista. Ainda não há front-end que as use.
- Se um aluno for inativado entre as duas consultas da lista de revisões vencidas (as
  fichas e os nomes), a ficha dele sai da página, que fica com um a menos, uma vez.
- Uma mudança de "ativo" direto no banco, sem a aplicação, não avisa os treinos: a ficha
  continua na contagem da lista, mas sem nome, e por isso fora da página.

## Verificação
- Testes novos em cada parte e mutações à mão: 19 no histórico imutável, 18 no texto, 20
  nas listas e 7 no teto, todas pegas, exceto uma equivalente (o `SUBSELECT` nos
  exercícios repete a consulta das divisões, que já é da página). Sete sobreviveram na
  primeira rodada e ganharam testes. Entre elas: o aviso não podia mudar a ficha encerrada
  (a inativação daria 500), e uma página de uma ficha só nem usava o `SUBSELECT`.
- Primeiro teste de migração com dados (`MigracaoDasRevisoesVencidasTest`): banco próprio
  até a V32, os dados, e então a V33.
- No Docker: com 320 MB e a opção, o OOM reinicia a aplicação em 6 s; sem a opção, ela
  ficou fora do ar por 3 minutos, sem reinício. Os ataques do diagnóstico, como
  `academia_app`, são recusados, e a montagem e a correção funcionam. Os 13 casos de byte
  nulo dão 400 sem nada no log. Na carga (1 GB, 60 mil vencidas, histórico de 2000), 16
  clientes por 15 s em cada lista só receberam 200, sem OOM nem reinício; a página do
  histórico tem 139 KB (eram 27 MB). e2e: 115 ok.
