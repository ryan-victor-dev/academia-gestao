"""E-mail de ponta a ponta (ADRs 0038 e 0039), na mesma instalação do e2e, depois de ligar o e-mail
com o Mailpit: a confirmação no primeiro acesso da equipe, o "esqueci a senha" e o primeiro acesso
do aluno. Os códigos são lidos da caixa do Mailpit, como a pessoa leria no e-mail. Rode pelo
scripts/e2e.sh, que liga o e-mail e passa as portas por variáveis de ambiente."""
import json, os, random, re, ssl, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

HTTPS = os.environ.get("E2E_HTTPS", "18443")
BASE = f"https://localhost:{HTTPS}"
MAILPIT = f"http://localhost:{os.environ.get('E2E_MAILPIT', '18025')}/api/v1"
CTX = ssl._create_unverified_context()  # certificado de desenvolvimento do Caddy
PASTA = sys.argv[1]
ok = falhas = 0


def req(metodo, caminho, corpo=None, token=None):
    s, b = _req(metodo, caminho, corpo, token)
    if s == 429 and caminho.startswith("/api/auth/"):
        # O limite por IP (ADR 0022) vale aqui também, e as fases anteriores acabaram de usá-lo.
        print(f"  (limite em {caminho}: esperando a janela)")
        time.sleep(61)
        s, b = _req(metodo, caminho, corpo, token)
    return s, b


def _req(metodo, caminho, corpo=None, token=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    dados = json.dumps(corpo).encode() if corpo is not None else None
    r = urllib.request.Request(BASE + caminho, data=dados, method=metodo, headers=h)
    try:
        with urllib.request.urlopen(r, context=CTX, timeout=30) as resp:
            t = resp.read().decode()
            return resp.status, (json.loads(t) if t.startswith(("{", "[")) else t)
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        return e.code, (json.loads(t) if t.startswith(("{", "[")) else t)


def login(email, senha):
    return req("POST", "/api/auth/login", {"email": email, "senha": senha})


def mailpit(metodo, caminho):
    with urllib.request.urlopen(urllib.request.Request(MAILPIT + caminho, method=metodo), timeout=10) as r:
        t = r.read().decode()
        return json.loads(t) if t.startswith("{") else t


def caixa(email, esperar=True):
    """As mensagens para o e-mail, da mais nova para a mais antiga. O envio é em segundo plano: espera chegar."""
    consulta = urllib.parse.quote(f'to:"{email}"')
    for _ in range(30 if esperar else 3):
        mensagens = mailpit("GET", f"/search?query={consulta}").get("messages") or []
        if mensagens and esperar:
            return mensagens
        time.sleep(1)
    return mailpit("GET", f"/search?query={consulta}").get("messages") or []


def codigo(email):
    mensagens = caixa(email)
    if not mensagens:
        return None, ""
    texto = mailpit("GET", f"/message/{mensagens[0]['ID']}")["Text"]
    achado = re.search(r"\b(\d{6})\b", texto)
    return (achado.group(1) if achado else None), texto


def status(resposta, esperado):
    """Para conferir só o código HTTP, mostrando o que veio se não for o esperado."""
    return resposta[0] == esperado, resposta[0]


def outro(cod):
    return "000000" if cod != "000000" else "111111"


def confere(descricao, condicao, detalhe=""):
    global ok, falhas
    if condicao:
        ok += 1
    else:
        falhas += 1
        # Nunca o token no log (CLAUDE.md), nem o de uma instalação de teste.
        print(f"  FALHOU: {descricao} {re.sub(r'eyJ[\w.-]+', '<token>', str(detalhe))}")


def secao(nome):
    print(f"- {nome}")


def cpf():
    n = [random.randint(0, 9) for _ in range(9)]
    for _ in range(2):
        s = sum(d * p for d, p in zip(n, range(len(n) + 1, 1, -1)))
        n.append(0 if s % 11 < 2 else 11 - s % 11)
    return "".join(map(str, n))


secao("e-mail ligado: a tela de login oferece as duas opções")
s, b = req("GET", "/api/auth/opcoes")
confere("esqueci a senha e primeiro acesso disponíveis", s == 200 and b == {"esqueciSenha": True, "primeiroAcesso": True},
        (s, b))
mailpit("DELETE", "/messages")

secao("ADMIN (não confirma e-mail)")
r = subprocess.run(["docker", "compose", "run", "--rm", "app", "--recuperar-admin=admin@academia.local"],
                   capture_output=True, text=True, cwd=PASTA)
linha = [l for l in r.stdout.splitlines() if "Senha temporária do ADMIN" in l]
confere("recuperação do ADMIN", r.returncode == 0 and len(linha) == 1, (r.returncode, r.stderr[-300:]))
temporaria = linha[0].split(": ")[-1] if linha else "nenhuma"
s, b = login("admin@academia.local", temporaria)
s, b = req("POST", "/api/auth/trocar-senha", {"senhaAtual": temporaria, "novaSenha": "senha-do-e2e-email-1"},
           b.get("token") if isinstance(b, dict) else None)
confere("ADMIN entra sem confirmar o e-mail", s == 200 and b["precisaConfirmarEmail"] is False, (s, b))
ADM = b["token"]

secao("confirmação do e-mail no primeiro acesso da equipe")
OLGA = "olga.email@academia.local"
s, b = req("POST", "/api/usuarios", {"nome": "Olga", "email": OLGA, "cpf": cpf(), "perfil": "RECEPCAO"}, ADM)
confere("ADMIN cria a recepcionista", s == 201, (s, b))
temporaria = b.get("senhaTemporaria", "nenhuma")
s, b = login(OLGA, temporaria)
confere("primeiro login: troca a senha antes", s == 200 and b["precisaTrocarSenha"] is True, (s, b))
s, b = req("POST", "/api/auth/trocar-senha", {"senhaAtual": temporaria, "novaSenha": "senha-da-olga-1"}, b["token"])
confere("depois da troca, ainda falta o e-mail", s == 200 and b["precisaConfirmarEmail"] is True, (s, b))
SEM_EMAIL = b["token"]
confere("sem confirmar, não usa o sistema", *status(req("GET", "/api/alunos", token=SEM_EMAIL), 403))
confere("pede o código", *status(req("POST", "/api/auth/email/codigo", token=SEM_EMAIL), 204))
cod, texto = codigo(OLGA)
confere("o código chega no e-mail, com a validade", cod is not None and "15 minutos" in texto, texto[:200])
confere("código errado é recusado", *status(req("POST", "/api/auth/email/confirmar", {"codigo": outro(cod)}, SEM_EMAIL), 400))
confere("código certo confirma", *status(req("POST", "/api/auth/email/confirmar", {"codigo": cod}, SEM_EMAIL), 204))
s, b = login(OLGA, "senha-da-olga-1")
confere("o login seguinte já não pede o e-mail", s == 200 and b["precisaConfirmarEmail"] is False, (s, b))
confere("e usa o sistema", *status(req("GET", "/api/alunos", token=b.get("token")), 200))

secao("esqueci a senha")
mailpit("DELETE", "/messages")
confere("e-mail cadastrado: 202", *status(req("POST", "/api/auth/esqueci-senha", {"email": OLGA}), 202))
confere("e-mail que não existe: o mesmo 202", *status(req("POST", "/api/auth/esqueci-senha", {"email": "ninguem.email@academia.local"}), 202))
cod, _ = codigo(OLGA)
confere("o código chega para quem existe", cod is not None)
confere("e nada chega para quem não existe", caixa("ninguem.email@academia.local", esperar=False) == [])
s, b = req("POST", "/api/auth/redefinir-senha", {"email": OLGA, "codigo": outro(cod), "novaSenha": "senha-nova-da-olga-2"})
confere("código errado não redefine", s == 400, (s, b))
s, b = req("POST", "/api/auth/redefinir-senha", {"email": OLGA, "codigo": cod, "novaSenha": "senha-nova-da-olga-2"})
confere("código certo redefine", s == 204, (s, b))
s, b = req("POST", "/api/auth/redefinir-senha", {"email": OLGA, "codigo": cod, "novaSenha": "outra-senha-da-olga-3"})
confere("o código vale uma vez só", s == 400, (s, b))
confere("a senha antiga não entra mais", *status(login(OLGA, "senha-da-olga-1"), 401))
confere("a nova entra", *status(login(OLGA, "senha-nova-da-olga-2"), 200))

secao("primeiro acesso do aluno")
mailpit("DELETE", "/messages")
LIA, CPF_LIA = "lia.email@email.com", cpf()
s, b = req("POST", "/api/alunos", {"nome": "Lia Campos", "cpf": CPF_LIA, "email": LIA}, ADM)
confere("aluna cadastrada com e-mail", s == 201, (s, b))
confere("CPF com o e-mail de outra pessoa: o mesmo 202", *status(req("POST", "/api/auth/primeiro-acesso", {"cpf": CPF_LIA, "email": "intruso.email@email.com"}), 202))
confere("pedido com o e-mail do cadastro: 202", *status(req("POST", "/api/auth/primeiro-acesso", {"cpf": CPF_LIA, "email": LIA}), 202))
cod, _ = codigo(LIA)
confere("o código chega para a aluna", cod is not None)
confere("e nada chega para o intruso", caixa("intruso.email@email.com", esperar=False) == [])
s, b = req("POST", "/api/auth/primeiro-acesso/senha", {"cpf": CPF_LIA, "email": LIA, "codigo": cod, "senha": "senha-da-lia-123"})
confere("a aluna cria a senha", s == 204, (s, b))
s, b = login(LIA, "senha-da-lia-123")
confere("e entra sem troca nem confirmação", s == 200 and not b["precisaTrocarSenha"] and not b["precisaConfirmarEmail"], (s, b))
s, b = req("GET", "/api/alunos/me", token=b.get("token"))
confere("vê o próprio cadastro", s == 200 and b.get("nome") == "Lia Campos", (s, b))
mailpit("DELETE", "/messages")
confere("com login, um novo pedido ainda dá 202", *status(req("POST", "/api/auth/primeiro-acesso", {"cpf": CPF_LIA, "email": LIA}), 202))
confere("mas não manda código", caixa(LIA, esperar=False) == [])

print(f"\nRESULTADO (e-mail): {ok} ok, {falhas} falha(s)")
sys.exit(1 if falhas else 0)
