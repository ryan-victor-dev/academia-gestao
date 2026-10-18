# ADR 0021 — Pendências médias da terceira revisão: limites, tempos, índices, datas e auditoria

- **Status:** Aceito (complementa os ADRs 0012, 0014 e 0020; adianta a auditoria da etapa 8)
- **Data:** 2026-10-17

## Contexto

O ADR 0020 corrigiu os três achados graves da terceira revisão e deixou seis de
gravidade média registrados como pendências. Este ADR registra como cada um foi
resolvido, as decisões do dono do projeto e os defeitos encontrados no caminho. Cada
pendência foi comprovada antes da correção, testada com mutação (o teste falha quando a
regra é quebrada) e validada no Docker.

## Decisões

### M1. Limite de requisições por usuário logado
- **Antes:** um token fez 1.081 requisições por segundo sem nenhum 429.
- **Regra (valor decidido pelo dono):** 300 requisições por minuto por usuário (o "sub"
  do token), em balde de fichas: rajada de até 300, que voltam a 5 por segundo. Passou
  disso: 429 com `Retry-After`. Configurável em `academia.requisicoes.por-minuto`.
- **Em memória, e não no banco** como o limite de login: gravar no banco a cada
  requisição gastaria uma conexão por chamada, o recurso que o limite protege. Com mais
  de uma instância da aplicação, cada uma contaria o seu.
- Fica logo depois da validação do token e conta só quem entrou com JWT; o login segue
  com os limites dele (por IP e por conta).
- O log avisa no máximo uma vez por minuto por usuário.

### M2. Pool de conexões e tempos máximos
- **Antes:** 10 conexões, espera de 30 s por conexão e nenhum teto por consulta: uma
  consulta presa numa trava ou um pico de acessos deixava todos esperando meio minuto
  por um 500.
- **Regra:** 20 conexões (`DB_POOL`), espera máxima de 3 s por conexão e
  `statement_timeout` de 5 s, só nas conexões da aplicação (migrations e backup usam
  outras). Banco sem conexão livre ou consulta longa demais: 503 com `Retry-After`.
- **Depois:** com uma linha travada e 30 edições simultâneas sobre ela, todas
  responderam 503 em até 5,1 s, e uma consulta a planos no meio respondeu em 3 s.

### M3. Trilha de auditoria (planos, alunos e usuários)
- **Antes:** só as matrículas guardavam quem cancelou, trancou e trocou. Mudar o preço
  ou a multa de um plano, editar ou inativar alunos e mexer na equipe não deixava rastro.
- **Escopo (decidido pelo dono):** planos, alunos e logins (equipe e alunos), com os
  valores de antes e depois, nunca senhas; consulta só para o ADMIN
  (`GET /api/auditoria`). O resto da etapa 8 (outras entidades, relatórios) continua
  pendente.
- **Desenho:** a porta `Auditoria` fica em `compartilhado` (o `auth` não pode depender de
  outros módulos) e a implementação no módulo `auditoria`. O registro vai na **mesma
  transação** da alteração (propagação `MANDATORY`: fora de uma transação, é recusado):
  se um falhar, nenhum dos dois fica.
- Grava só os campos que mudaram (`{"campo": {"antes", "depois"}}`). O autor é o "sub" do
  token; vazio quando foi o próprio sistema (primeiro ADMIN, recuperação pela linha de
  comando). Sem chave estrangeira para `usuarios`: a trilha não impede apagar um usuário
  nem some junto com ele.
- O usuário da aplicação no banco só insere e lê a trilha (`UPDATE`, `DELETE` e
  `TRUNCATE` revogados): nem uma injeção de SQL reescreve o histórico.

### M4. Datas extremas
- **Antes:** matrícula com início em 9999-12-31 era aceita, e um ano gigante
  (`+999999999`) estourava o cálculo do fim (500).
- **Regras (decididas pelo dono):** o início da matrícula pode ser de hoje até **1 ano**
  à frente; a data de nascimento do aluno, de até **120 anos** atrás.
- **Defeito encontrado ao reproduzir:** uma data de nascimento que o Postgres não guarda
  respondia **409 "CPF já cadastrado"**, porque qualquer recusa do banco caía no mesmo
  `catch` do CPF repetido. Agora só a violação do índice de CPF (`alunos_cpf_key`) vira
  409; um teste leva o CPF repetido até o índice, sem depender de sorte, e confere o
  nome real.

### M5. Limites de recursos em todos os containers
- **Antes:** só a aplicação tinha teto de memória; nenhum container tinha limite de CPU,
  num servidor dividido com outros serviços.
- **Regra:** teto de memória, CPU e processos em todos: aplicação e Postgres com 1 GiB e
  2 núcleos (ajustáveis no `.env`), Caddy 256 MiB e 1 núcleo, backup 512 MiB e 1 núcleo.
- **Depois:** 600 logins simultâneos deixaram a aplicação em ~200% de CPU (2 núcleos);
  150 processos pedidos no container de backup pararam em 100. O e2e confere os três
  tetos em cada container.

### M6. Índices da busca de alunos
- Índice de trigramas sobre `nome_normalizado(nome)` (sem acento e minúsculo, a função
  imutável dos planos), que acha `%texto%` em qualquer parte do nome; índice
  `(nome, id)` para a ordem da listagem; índice do CPF com `bpchar_pattern_ops`, porque
  com a collation `en_US` o índice único não serve para `LIKE '529%'`.
- A busca passou a usar `nome_normalizado`: com outra expressão
  (`lower(unaccent(...))`), o índice não vale. Um teste confere, pelas estatísticas do
  banco, que as consultas geradas pela aplicação usam cada índice.
- **Medido** numa instalação isolada com 50 mil alunos, mesmos dados e mesmos limites de
  CPU, sem e com os índices: busca por nome de 6 para 135 req/s com 1 cliente (154 ms →
  7 ms) e, com 64 clientes, de 11 req/s com 91 erros para 198 req/s sem erros; 1ª página
  de 92 para 398 req/s e começo do CPF de 410 para 4.096 req/s, com 16 clientes.

## Defeitos encontrados no caminho

- **Recuperação de ADMIN quebrada:** o comando sobe a aplicação sem servidor web, e os
  componentes novos das partes M2 e M1 exigiam beans que só existem com web. Os testes
  não viram porque todos sobem com web; o e2e pegou. Agora esses componentes só existem
  com web, e o `ModoComandoTest` sobe a aplicação como o comando real.
- **500 com o pool esgotado na validação do token:** a validação consulta o banco ainda
  no filtro de segurança, fora do alcance do tratador de erros. Os testes usavam token
  simulado, que pula essa consulta; a validação no Docker pegou.
- **Teste instável do limitador de login:** o relógio do teste era fixo no passado, e a
  limpeza agendada da própria aplicação (relógio real) apagava as tentativas do teste no
  meio de uma rodada. Comprovado com um experimento e corrigido no teste.

## Consequências

- O teto de 2 núcleos por container limita a vazão (~200 buscas por nome por segundo,
  ~30 logins por segundo): de sobra para uma academia, ajustável no `.env`.
- Qualquer consulta futura que precise passar de 5 s (um relatório pesado, por
  exemplo) precisa de conexão própria ou de um ajuste explícito do `statement_timeout`.
- A trilha guarda valores anteriores de dados pessoais (CPF, telefone, e-mail) sem
  prazo de retenção. Pela LGPD, o prazo é uma decisão a tomar com o dono.
- Busca por nome com menos de 3 letras não usa o índice de trigramas (varre a tabela).

## Pendências que continuam

As baixas da revisão (erro não tratado fora de `problem+json`, CVEs sem correção nas
imagens, proxy confiável amplo demais, privilégio de `DELETE` sobrando, script de
usuário de validação, getters sem teste de unidade) e os itens de arquitetura (tamanho
da `Matricula`, regras duplicadas entre plano e matrícula, relógio único, limpeza
central dos testes, trancamentos carregados junto). Encontrado agora: o `PlanoService`
traduz qualquer recusa do banco para "nome em uso", o mesmo defeito corrigido no CPF
(hoje inalcançável pela API, porque o domínio valida tudo antes).
