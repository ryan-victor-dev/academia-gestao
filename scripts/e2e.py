"""Teste de ponta a ponta contra uma instalação do zero. Rode pelo scripts/e2e.sh, que monta
a instalação isolada, passa as portas e os nomes por variáveis de ambiente e desmonta tudo."""
import json, os, ssl, subprocess, sys, time, urllib.request, urllib.error
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

HTTPS = os.environ.get("E2E_HTTPS", "18443")
HTTP = os.environ.get("E2E_HTTP", "18089")
P = os.environ.get("E2E_PREFIXO", "e2e-")                    # prefixo dos containers
PROJETO = os.environ.get("COMPOSE_PROJECT_NAME", "academia-e2e")
BASE = f"https://localhost:{HTTPS}"
CTX = ssl._create_unverified_context()  # certificado de desenvolvimento do Caddy
PASTA = sys.argv[1]
ok = falhas = 0


def req(metodo, caminho, corpo=None, token=None, headers=None, bruto=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    dados = bruto if bruto is not None else (json.dumps(corpo).encode() if corpo is not None else None)
    r = urllib.request.Request(BASE + caminho, data=dados, method=metodo, headers=h)
    try:
        with urllib.request.urlopen(r, context=CTX, timeout=30) as resp:
            t = resp.read().decode()
            return resp.status, (json.loads(t) if t.startswith(("{", "[")) else t), resp.headers
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        return e.code, (json.loads(t) if t.startswith(("{", "[")) else t), e.headers


def sh(*cmd):
    return subprocess.run(list(cmd), capture_output=True, text=True, cwd=PASTA)


def confere(descricao, condicao, detalhe=""):
    global ok, falhas
    if condicao:
        ok += 1
    else:
        falhas += 1
        print(f"  FALHOU: {descricao} {detalhe}")


def secao(nome):
    print(f"- {nome}")


def login(email, senha):
    return req("POST", "/api/auth/login", {"email": email, "senha": senha})


def ativar(email, temporaria, definitiva):
    s, b, _ = login(email, temporaria)
    confere(f"1º login de {email}", s == 200 and b["precisaTrocarSenha"] is True, (s, b))
    s, b, _ = req("POST", "/api/auth/trocar-senha", {"senhaAtual": temporaria, "novaSenha": definitiva}, b["token"])
    confere(f"troca de senha de {email}", s == 200 and b["precisaTrocarSenha"] is False, (s, b))
    return b["token"]


secao("containers endurecidos")
uid_caddy = sh("docker", "top", P + "caddy").stdout.splitlines()[1].split()[0]
confere("Caddy sem root", uid_caddy == "10001", uid_caddy)
cap = sh("docker", "exec", P + "caddy", "sh", "-c", "grep CapEff /proc/1/status").stdout.split()[-1]
confere("Caddy só com NET_BIND_SERVICE", cap == "0000000000000400", cap)
r = sh("docker", "exec", P + "app", "sh", "-c", "touch /app/x")
confere("app com sistema de arquivos só de leitura", r.returncode != 0 and "Read-only" in r.stderr, r.stderr)
for c in (P + "app", P + "caddy", P + "postgres"):
    info = sh("docker", "inspect", c, "--format", "{{.HostConfig.LogConfig.Config}} {{.HostConfig.SecurityOpt}}").stdout
    confere(f"{c} com rotação de log e no-new-privileges", "max-size:10m" in info and "no-new-privileges" in info, info)
for c in (P + "app", P + "caddy", P + "postgres", P + "backup", f"{PROJETO}-caddy-permissoes-1"):
    # Sem limite, o Docker devolve 0 ou "<no value>": só número positivo conta.
    limites = sh("docker", "inspect", c, "--format",
                 "{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.HostConfig.PidsLimit}}").stdout.strip().split("|")
    confere(f"{c} com teto de memória, CPU e processos", all(v.isdigit() and int(v) > 0 for v in limites),
            "memória={} cpu={} processos={}".format(*limites))
confere("sem pebble na imagem", sh("docker", "exec", P + "app", "sh", "-c", "ls /usr/bin/pebble").returncode != 0)

secao("entrada HTTPS")
s, b, h = req("GET", "/actuator/health")
confere("health via HTTPS", s == 200 and b["status"] == "UP")
confere("HSTS", "max-age" in (h.get("Strict-Transport-Security") or ""))
r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{redirect_url}", f"http://localhost:{HTTP}/x"],
                   capture_output=True, text=True).stdout
confere("HTTP redireciona para HTTPS", r.startswith(f"301 https://localhost:{HTTPS}/x"), r)
r = subprocess.run(["curl", "-sk", "--http2", "-o", "/dev/null", "-w", "%{http_version}", BASE + "/actuator/health"],
                   capture_output=True, text=True).stdout
confere("só HTTP/1.1", r == "1.1", r)

secao("primeiro ADMIN")
s, b, _ = login("admin@academia.local", "senha-primeiro-acesso-123")
confere("login com a senha do .env exige troca", s == 200 and b["precisaTrocarSenha"])
ADM = ativar("admin@academia.local", "senha-primeiro-acesso-123", "senha-definitiva-admin-1")

secao("equipe")
tokens = {}
for nome, perfil in [("Rita", "RECEPCAO"), ("Caio", "INSTRUTOR")]:
    s, b, h = req("POST", "/api/usuarios", {"nome": nome, "email": f"{nome.lower()}@academia.local", "perfil": perfil}, ADM)
    confere(f"criar {perfil}", s == 201 and h.get("Cache-Control") == "no-store", (s, b))
    tokens[perfil] = ativar(f"{nome.lower()}@academia.local", b["senhaTemporaria"], f"senha-definitiva-{nome.lower()}-1")
REC, INS = tokens["RECEPCAO"], tokens["INSTRUTOR"]
confere("recepção não cria usuário",
        req("POST", "/api/usuarios", {"nome": "X", "email": "x@academia.local", "perfil": "ADMIN"}, REC)[0] == 403)

secao("alunos")
s, b, h = req("POST", "/api/alunos", {"nome": "José Antônio", "cpf": "529.982.247-25", "email": "jose@email.com",
                                      "observacoes": "lesão no joelho"}, REC)
confere("cadastrar aluno", s == 201 and h.get("ETag") == '"0"', (s, b))
ALUNO = b["id"]
confere("CPF inválido (só o 1º dígito errado)", req("POST", "/api/alunos", {"nome": "X", "cpf": "52998224733"}, REC)[0] == 400)
confere("busca sem acento", req("GET", "/api/alunos?busca=jose", token=REC)[1]["totalElementos"] == 1)
s, b, _ = req("GET", f"/api/alunos/{ALUNO}", token=INS)
confere("instrutor sem CPF", s == 200 and "cpf" not in b)
corpo = {"nome": "José Antônio Silva", "cpf": "52998224725", "email": "jose@email.com"}
confere("PUT sem If-Match = 428", req("PUT", f"/api/alunos/{ALUNO}", corpo, REC)[0] == 428)
confere("PUT com If-Match", req("PUT", f"/api/alunos/{ALUNO}", corpo, REC, {"If-Match": '"0"'})[0] == 200)
confere("If-Match antigo = 412", req("PUT", f"/api/alunos/{ALUNO}", corpo, REC, {"If-Match": '"0"'})[0] == 412)
s, b, _ = req("POST", f"/api/alunos/{ALUNO}/login", token=REC)
TOK_ALUNO = ativar("jose@email.com", b["senhaTemporaria"], "senha-do-jose-123")
confere("aluno vê o próprio cadastro", req("GET", "/api/alunos/me", token=TOK_ALUNO)[0] == 200)
req("POST", f"/api/alunos/{ALUNO}/inativar", token=REC)
confere("inativar derruba o token do aluno", req("GET", "/api/alunos/me", token=TOK_ALUNO)[0] == 401)

secao("planos")
s, b, _ = req("POST", "/api/planos", {"nome": "Mensal", "valorMensal": 129.9, "duracaoMeses": 1}, ADM)
confere("criar plano", s == 201, (s, b))
confere("nome repetido com acento", req("POST", "/api/planos", {"nome": "MÊNSAL", "valorMensal": 1, "duracaoMeses": 1}, ADM)[0] == 409)
confere("recepção não cria plano", req("POST", "/api/planos", {"nome": "Z", "valorMensal": 1, "duracaoMeses": 1}, REC)[0] == 403)

secao("matrículas")
hoje = datetime.now(ZoneInfo("America/Sao_Paulo")).date()
MENSAL = next(p["id"] for p in req("GET", "/api/planos", token=REC)[1] if p["nome"] == "Mensal")
s, b, _ = req("POST", "/api/planos", {"nome": "Anual", "valorMensal": 99.9, "duracaoMeses": 12, "fidelidade": True,
                                      "percentualMulta": 20, "formaPagamento": "A_VISTA"}, ADM)
confere("plano anual com fidelidade e à vista", s == 201 and b["fidelidade"] and b["formaPagamento"] == "A_VISTA", (s, b))
ANUAL = b["id"]
s, b, _ = req("POST", "/api/alunos", {"nome": "Maria", "cpf": "111.444.777-35", "email": "maria@email.com"}, REC)
MARIA = b["id"]
s, b, _ = req("POST", f"/api/alunos/{MARIA}/login", token=REC)
TOK_MARIA = ativar("maria@email.com", b["senhaTemporaria"], "senha-da-maria-123")
s, m, _ = req("POST", "/api/matriculas", {"alunoId": MARIA, "planoId": ANUAL}, REC)
confere("matricular copia o plano", s == 201 and m["valorMensal"] == 99.9 and m["fidelidade"] and m["situacao"] == "VIGENTE"
        and m["inicio"] == hoje.isoformat(), (s, m))
s, _, h = req("GET", f"/api/planos/{ANUAL}", token=ADM)
req("PUT", f"/api/planos/{ANUAL}", {"nome": "Anual", "valorMensal": 119.9, "duracaoMeses": 12}, ADM, {"If-Match": h["ETag"]})
s, b, _ = req("GET", f"/api/matriculas/{m['id']}", token=REC)
confere("reajuste e fim da fidelidade do plano não mudam o contrato", b["valorMensal"] == 99.9 and b["fidelidade"], b)
fim = date.fromisoformat(m["fim"])
confere("período ocupado = 409", req("POST", "/api/matriculas", {"alunoId": MARIA, "planoId": MENSAL,
                                                                  "inicio": fim.isoformat()}, REC)[0] == 409)
s, r, _ = req("POST", "/api/matriculas", {"alunoId": MARIA, "planoId": MENSAL, "inicio": (fim + timedelta(days=1)).isoformat()}, REC)
confere("renovação agendada", s == 201 and r["situacao"] == "AGENDADA", (s, r))
confere("trancar com renovação agendada = 409", req("POST", f"/api/matriculas/{m['id']}/trancar",
        {"voltaPrevista": (hoje + timedelta(days=10)).isoformat(), "motivo": "Viagem"}, REC)[0] == 409)
s, b, _ = req("POST", f"/api/matriculas/{r['id']}/cancelar", {"motivo": "Vai trancar antes"}, REC)
confere("cancelar a renovação (agendada, sem multa)", s == 200 and b["situacao"] == "CANCELADA" and b["multa"] is None, (s, b))
s, b, _ = req("POST", f"/api/matriculas/{m['id']}/trancar", {"voltaPrevista": (hoje + timedelta(days=10)).isoformat(),
                                                            "motivo": "Viagem"}, REC)
confere("trancar estende o fim", s == 200 and b["situacao"] == "TRANCADA" and b["diasTrancados"] == 10
        and b["fim"] == (fim + timedelta(days=10)).isoformat(), (s, b))
confere("instrutor: trancada não treina",
        req("GET", f"/api/matriculas/vigente?alunoId={MARIA}", token=INS)[1] == {"alunoId": MARIA, "podeTreinar": False})
s, b, _ = req("POST", f"/api/matriculas/{m['id']}/registrar-volta", token=REC)
confere("volta no mesmo dia desfaz o trancamento", s == 200 and b["diasTrancados"] == 0 and b["situacao"] == "VIGENTE", (s, b))
s, nova, _ = req("POST", f"/api/matriculas/{m['id']}/trocar-plano", {"planoId": MENSAL}, REC)
confere("trocar para o mensal sem fidelidade", s == 201 and nova["trocaDe"] == m["id"] and nova["inicio"] == hoje.isoformat(), (s, nova))
s, b, _ = req("GET", f"/api/matriculas/{m['id']}", token=REC)
confere("antiga TROCADA com multa (11 × 99,90 × 20% = 219,78)", b["situacao"] == "TROCADA" and b["multa"] == 219.78, b)
s, b, _ = req("POST", f"/api/alunos/{MARIA}/inativar", token=REC)
confere("inativar com matrícula vigente = 409", s == 409, (s, b))
s, b, _ = req("GET", "/api/matriculas/me", token=TOK_MARIA)
confere("aluna vê as próprias, sem quem cancelou", s == 200 and len(b) == 3 and all("canceladaPor" not in x for x in b), (s, b))
confere("instrutor não vê o histórico", req("GET", f"/api/matriculas?alunoId={MARIA}", token=INS)[0] == 403)
req("POST", f"/api/matriculas/{nova['id']}/cancelar", {"motivo": "Mudou de cidade"}, REC)
confere("depois de cancelar, inativa", req("POST", f"/api/alunos/{MARIA}/inativar", token=REC)[0] == 200)
confere("aluno inativo não é matriculado",
        req("POST", "/api/matriculas", {"alunoId": ALUNO, "planoId": MENSAL}, REC)[0] == 409)

secao("cobrança")
def sql_e2e(consulta):
    return sh("docker", "exec", P + "postgres", "psql", "-U", "academia", "-d", "academia", "-At", "-c", consulta).stdout.strip()
s, b, h = req("GET", "/api/cobrancas/configuracao", token=REC)
confere("configuração padrão do dono (2%, 1% ao mês, 10 dias)", s == 200 and (b["tipoAcrescimo"], b["acrescimo"], b["jurosAoMes"],
        b["diasParaBloquear"]) == ("PERCENTUAL", 2.0, 1.0, 10), (s, b))
s, cs, _ = req("GET", f"/api/cobrancas?alunoId={MARIA}", token=REC)
por_tipo = {(c["tipo"], c["situacao"]): c for c in cs} if s == 200 else {}
confere("multa da troca vira cobrança de 219,78", ("MULTA", "PENDENTE") in por_tipo
        and por_tipo[("MULTA", "PENDENTE")]["valor"] == 219.78, cs)
confere("à vista trocada no dia do vencimento: cancelada pelo sistema", ("A_VISTA", "CANCELADA") in por_tipo
        and por_tipo[("A_VISTA", "CANCELADA")]["cancelamento"]["por"] is None, cs)
confere("mensal cancelada no 1º dia: fica a 1ª, as outras não existem", [c["parcela"] for c in cs
        if c["tipo"] == "MENSALIDADE" and c["situacao"] == "PENDENTE"] == [1], cs)
multa = por_tipo.get(("MULTA", "PENDENTE"), {}).get("id")
s, b, _ = req("POST", f"/api/cobrancas/{multa}/pagar", {"valor": 200, "meioDePagamento": "PIX"}, REC)
confere("pagar valor diferente = 409 com o devido", s == 409 and b["devidoHoje"]["total"] == 219.78, (s, b))
s, b, _ = req("POST", f"/api/cobrancas/{multa}/pagar", {"valor": 219.78, "meioDePagamento": "PIX"}, REC)
confere("recepção recebe a multa", s == 200 and b["situacao"] == "PAGA", (s, b))
confere("recepção não estorna", req("POST", f"/api/cobrancas/{multa}/estornar", {"motivo": "x"}, REC)[0] == 403)
s, b, _ = req("POST", f"/api/cobrancas/{multa}/estornar", {"motivo": "Pix devolvido"}, ADM)
confere("ADMIN estorna: volta a dever", s == 200 and b["situacao"] == "PENDENTE" and b["pagamento"] is None, (s, b))
s, b, _ = req("GET", f"/api/auditoria?entidade=COBRANCA&entidadeId={multa}", token=ADM)
confere("trilha do pagamento e do estorno", s == 200 and [r["acao"] for r in b["conteudo"]] == ["ESTORNADA", "PAGA"], (s, b))

s, b, _ = req("POST", "/api/alunos", {"nome": "Ana Atrasada", "cpf": "390.533.447-05"}, REC)
ANA = b["id"]
s, m, _ = req("POST", "/api/matriculas", {"alunoId": ANA, "planoId": MENSAL}, REC)
confere("mensal gera as mensalidades", s == 201 and len(req("GET", f"/api/cobrancas?alunoId={ANA}", token=REC)[1]) == 1, (s, m))
pode = lambda: req("GET", f"/api/matriculas/vigente?alunoId={ANA}", token=INS)[1].get("podeTreinar")
confere("em dia: pode treinar", pode() is True)
# Sem mexer no relógio do servidor: a mensalidade passa a ter vencido há 11 dias.
sql_e2e(f"UPDATE cobrancas SET vencimento = vencimento - 11 WHERE aluno_id = '{ANA}'")
confere("11 dias de atraso: não pode treinar", pode() is False)
s, at, _ = req("GET", "/api/cobrancas/atrasadas", token=REC)
atrasada = next((c for c in at.get("conteudo", []) if c["alunoId"] == ANA), None)
# 129,90 + 2% (2,60) + 1% ao mês por 11 dias (0,48)
confere("na lista de atrasadas, com acréscimo e juros", atrasada is not None and atrasada["devidoHoje"]["total"] == 132.98, at)
s, _, h = req("GET", "/api/cobrancas/configuracao", token=ADM)
etag = {k.lower(): v for k, v in h.items()}.get("etag")
s, b, _ = req("PUT", "/api/cobrancas/configuracao", {"tipoAcrescimo": "VALOR", "acrescimo": 7, "jurosAoMes": 1,
              "diasParaBloquear": 30}, ADM, {"If-Match": etag})
confere("ADMIN muda para R$ 7 e 30 dias", s == 200, (s, b))
confere("com 30 dias de tolerância, volta a treinar", pode() is True)
s, c, _ = req("GET", f"/api/cobrancas/{atrasada['id']}", token=REC) if atrasada else (0, {}, {})
confere("R$ 7 cortado no teto de 2% (2,60)", c.get("devidoHoje", {}).get("acrescimo") == 2.6, c)
s, b, _ = req("POST", f"/api/cobrancas/{atrasada['id']}/pagar", {"valor": 132.98, "meioDePagamento": "DINHEIRO"}, REC)
confere("recepção recebe 132,98", s == 200 and b["situacao"] == "PAGA", (s, b))
confere("cobranças só para a equipe", req("GET", f"/api/cobrancas?alunoId={ANA}", token=INS)[0] == 403)

secao("cabeçalhos e JSON estrito")
s, b, h = req("GET", "/actuator/health")
confere("CSP", h.get("Content-Security-Policy") == "default-src 'none'; frame-ancestors 'none'", h.get("Content-Security-Policy"))
confere("Referrer-Policy", h.get("Referrer-Policy") == "no-referrer")
confere("duracaoMeses 1.5 recusado",
        req("POST", "/api/planos", bruto=b'{"nome":"J1","valorMensal":10,"duracaoMeses":1.5}', token=ADM)[0] == 400)
confere("valor como texto recusado",
        req("POST", "/api/planos", bruto=b'{"nome":"J2","valorMensal":"10","duracaoMeses":1}', token=ADM)[0] == 400)

secao("limites")
confere("corpo grande = 413",
        req("POST", "/api/auth/login", bruto=json.dumps({"email": "a@b.com", "senha": "x" * 200_000}).encode())[0] == 413)
time.sleep(61)  # o roteiro sai de um IP só: espera a janela do limite por IP
s, b, _ = login("caio@academia.local", "senha-definitiva-caio-1")
TOK = b["token"]
codigos = [req("POST", "/api/auth/trocar-senha", {"senhaAtual": f"chute-{i}-xxxxxx", "novaSenha": "nova-senha-x-123"}, TOK)[0]
           for i in range(12)]
confere("troca de senha: 10 chutes e depois 429", codigos == [400] * 10 + [429] * 2, codigos)
sh("docker", "compose", "restart", "app")
for _ in range(60):
    try:
        if req("GET", "/actuator/health")[0] == 200:
            break
    except Exception:
        pass
    time.sleep(2)
confere("bloqueio sobrevive ao reinício (app só de leitura)", login("caio@academia.local", "senha-definitiva-caio-1")[0] == 429)

secao("recuperação de ADMIN (container só de leitura)")
time.sleep(61)
r = sh("docker", "compose", "run", "--rm", "app", "--recuperar-admin=admin@academia.local")
linha = [l for l in r.stdout.splitlines() if "Senha temporária do ADMIN" in l]
confere("comando sai com 0 e mostra a senha uma vez", r.returncode == 0 and len(linha) == 1, (r.returncode, r.stderr[-300:]))
temporaria = linha[0].split(": ")[-1] if linha else "nenhuma"
s, b, _ = login("admin@academia.local", temporaria)
confere("temporária exige troca", s == 200 and b.get("precisaTrocarSenha"), (s, b))

secao("reinício do Caddy (permissões dos volumes)")
sh("docker", "compose", "restart", "caddy")
time.sleep(3)
confere("Caddy volta depois de reiniciar", req("GET", "/actuator/health")[0] == 200)
sh("docker", "compose", "up", "-d")
time.sleep(3)
confere("init de permissões roda de novo sem erro",
        sh("docker", "inspect", f"{PROJETO}-caddy-permissoes-1", "--format", "{{.State.ExitCode}}").stdout.strip() == "0")

secao("backup e restauração")
def sql(consulta):
    return sh("docker", "exec", P + "postgres", "psql", "-U", "academia", "-d", "academia", "-At", "-c", consulta).stdout.strip()
confere("rotina de backup no ar", "Rotina de backup" in sh("docker", "logs", P + "backup").stdout)
r = sh("docker", "compose", "run", "--rm", "-T", "backup", "agora")
dumps = sorted(os.listdir(os.path.join(PASTA, "backups", "diario"))) if r.returncode == 0 else []
confere("backup na hora gera um arquivo legível", r.returncode == 0 and len(dumps) == 1, (r.returncode, r.stdout[-200:], r.stderr[-300:]))
alunos_antes = sql("SELECT count(*) FROM alunos")
sql("INSERT INTO planos (id, nome, valor_mensal, duracao_meses) VALUES (gen_random_uuid(), 'Depois do backup', 1, 1)")
ambiente = {**os.environ, "PG_CONTAINER": P + "postgres", "APP_PORTA": "18090"}
r = subprocess.run(["scripts/restaurar-backup.sh", os.path.join("backups", "diario", dumps[0]) if dumps else "x", "--sim"],
                   capture_output=True, text=True, cwd=PASTA, env=ambiente)
confere("restaurar sai com 0", r.returncode == 0, (r.returncode, r.stderr[-300:]))
confere("o dado de depois do backup sumiu", sql("SELECT count(*) FROM planos WHERE nome = 'Depois do backup'") == "0")
confere("os dados do backup voltaram", sql("SELECT count(*) FROM alunos") == alunos_antes, (alunos_antes, sql("SELECT count(*) FROM alunos")))
confere("cópia de segurança do estado anterior", any(n.startswith("antes-de-restaurar-") for n in os.listdir(os.path.join(PASTA, "backups"))))
confere("aplicação no ar depois de restaurar", req("GET", "/actuator/health")[0] == 200)
confere("sem --sim, não restaura", subprocess.run(["scripts/restaurar-backup.sh", "x"], capture_output=True, text=True,
                                                   cwd=PASTA, env=ambiente).returncode == 1)

print(f"\nRESULTADO: {ok} ok, {falhas} falha(s)")
sys.exit(1 if falhas else 0)
