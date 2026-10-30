package br.com.academiagestao.auth;

import br.com.academiagestao.compartilhado.Cpf;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    private UUID id;

    @Column(nullable = false, length = 150)
    private String nome;

    @Column(nullable = false, length = 150)
    private String email;

    // Só da equipe, e nulo em quem foi cadastrado antes do ADR 0047 (o login de aluno nunca tem).
    @Column(length = 11)
    private String cpf;

    @Column(name = "senha_hash", nullable = false, length = 100)
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Perfil perfil;

    // Só o id, sem @ManyToOne: o módulo auth não depende das classes do módulo alunos.
    @Column(name = "aluno_id")
    private UUID alunoId;

    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "precisa_trocar_senha", nullable = false)
    private boolean precisaTrocarSenha;

    // Login de aluno inativo: entra, mas só consulta (ADR 0029). Vai no token.
    @Column(name = "somente_leitura", nullable = false)
    private boolean somenteLeitura;

    // Vai no token. Aumentar o número invalida todos os tokens já emitidos.
    @Column(name = "versao_token", nullable = false)
    private int versaoToken;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    // Nulo: o e-mail não foi confirmado (ADR 0038).
    @Column(name = "email_confirmado_em")
    private Instant emailConfirmadoEm;

    // Exigido pelo JPA. Protegido para ninguém criar um Usuario vazio pelo código.
    protected Usuario() {
    }

    private Usuario(String nome, String email, String senhaHash, Perfil perfil, UUID alunoId) {
        this.id = UUID.randomUUID();
        this.nome = nome.trim();
        this.email = normalizarEmail(email);
        this.senhaHash = senhaHash;
        this.perfil = perfil;
        this.alunoId = alunoId;
        this.ativo = true;
        this.criadoEm = Instant.now();
    }

    /** Usuário da equipe: ADMIN, RECEPCAO ou INSTRUTOR. */
    public static Usuario daEquipe(String nome, String email, String senhaHash, Perfil perfil) {
        validarComuns(nome, email, senhaHash);
        Objects.requireNonNull(perfil, "perfil é obrigatório");
        if (perfil == Perfil.ALUNO) {
            throw new IllegalArgumentException("Use Usuario.deAluno(...) para o perfil ALUNO");
        }
        return new Usuario(nome, email, senhaHash, perfil, null);
    }

    /** Login de um aluno, sempre ligado ao cadastro dele. */
    public static Usuario deAluno(String nome, String email, String senhaHash, UUID alunoId) {
        validarComuns(nome, email, senhaHash);
        Objects.requireNonNull(alunoId, "alunoId é obrigatório para o perfil ALUNO");
        return new Usuario(nome, email, senhaHash, Perfil.ALUNO, alunoId);
    }

    public static String normalizarEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validarComuns(String nome, String email, String senhaHash) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome é obrigatório");
        }
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("e-mail inválido");
        }
        if (senhaHash == null || senhaHash.isBlank()) {
            throw new IllegalArgumentException("senhaHash é obrigatório");
        }
    }

    public void renomear(String novoNome) {
        if (novoNome == null || novoNome.isBlank()) {
            throw new IllegalArgumentException("nome é obrigatório");
        }
        this.nome = novoNome.trim();
    }

    /** O CPF de alguém da equipe (ADR 0047), guardado só com os dígitos. */
    public void informarCpf(String novoCpf) {
        if (perfil == Perfil.ALUNO) {
            throw new IllegalStateException("o CPF do aluno fica no cadastro do aluno");
        }
        if (novoCpf == null || !Cpf.ehValido(novoCpf)) {
            throw new IllegalArgumentException("CPF inválido");
        }
        this.cpf = Cpf.normalizar(novoCpf);
    }

    /**
     * Muda o perfil de alguém da equipe. O perfil vai dentro do token: se mudou, os tokens
     * antigos são revogados, senão um ADMIN rebaixado manteria os poderes até expirar.
     */
    public void mudarPerfil(Perfil novoPerfil) {
        Objects.requireNonNull(novoPerfil, "perfil é obrigatório");
        if (novoPerfil == Perfil.ALUNO || this.perfil == Perfil.ALUNO) {
            throw new IllegalArgumentException("o perfil ALUNO não é trocado por aqui");
        }
        if (novoPerfil != this.perfil) {
            this.perfil = novoPerfil;
            revogarTokens();
        }
    }

    public void desativar() {
        this.ativo = false;
        revogarTokens();
    }

    // Reativar também revoga: um token de antes da desativação não volta a valer.
    public void reativar() {
        this.ativo = true;
        revogarTokens();
    }

    /**
     * Aluno inativado: o login continua, mas só para consultar os próprios dados (ADR 0029).
     * Os tokens de antes são revogados: um token com acesso completo não sobrevive.
     */
    public void restringirALeitura() {
        exigirAluno();
        this.somenteLeitura = true;
        revogarTokens();
    }

    /** Aluno reativado: o login volta ao uso normal, e os tokens só de leitura deixam de valer. */
    public void liberarDaLeitura() {
        exigirAluno();
        this.somenteLeitura = false;
        revogarTokens();
    }

    private void exigirAluno() {
        if (perfil != Perfil.ALUNO) {
            throw new IllegalStateException("só login de aluno fica só de leitura");
        }
    }

    /** A senha atual é temporária: o usuário só poderá usar o sistema depois de trocá-la. */
    public void exigirTrocaDeSenha() {
        this.precisaTrocarSenha = true;
    }

    /** Grava a nova senha (já em hash) e libera o uso normal do sistema. */
    public void trocarSenha(String novoHash) {
        if (novoHash == null || novoHash.isBlank()) {
            throw new IllegalArgumentException("senhaHash é obrigatório");
        }
        this.senhaHash = novoHash;
        this.precisaTrocarSenha = false;
        // Quem tinha a senha antiga (inclusive um invasor) perde as sessões abertas.
        revogarTokens();
    }

    /** "Esqueci minha senha": nova senha temporária, troca obrigatória e sessões derrubadas. */
    public void redefinirComSenhaTemporaria(String novoHash) {
        trocarSenha(novoHash);
        this.precisaTrocarSenha = true;
    }

    /**
     * O código chegou a este e-mail (ADR 0038). {@code emailDoCodigo} é para onde o código foi:
     * se o e-mail do login mudou depois, ele não confirma o novo.
     */
    public void confirmarEmail(String emailDoCodigo, Instant agora) {
        if (!email.equals(emailDoCodigo)) {
            throw new IllegalStateException("o código foi para outro e-mail");
        }
        this.emailConfirmadoEm = agora.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** O próprio usuário corrige o e-mail antes de confirmar; o novo também precisa ser confirmado. */
    public void corrigirEmail(String novoEmail) {
        if (emailConfirmadoEm != null) {
            throw new IllegalStateException("e-mail já confirmado não se corrige por aqui");
        }
        this.email = normalizarEmail(novoEmail);
    }

    /**
     * A recepção trocou o e-mail no cadastro do aluno (decisão do dono, ADR 0045): o login acompanha,
     * e o novo precisa ser confirmado, como no primeiro acesso. A senha e as sessões continuam.
     */
    public void acompanharEmailDoCadastro(String novoEmail) {
        if (novoEmail == null || !novoEmail.contains("@")) {
            throw new IllegalArgumentException("e-mail inválido");
        }
        String normalizado = normalizarEmail(novoEmail);
        if (!normalizado.equals(email)) {
            this.email = normalizado;
            this.emailConfirmadoEm = null;
        }
    }

    private void revogarTokens() {
        this.versaoToken++;
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }
    public String getCpf() { return cpf; }
    public String getSenhaHash() { return senhaHash; }
    public Perfil getPerfil() { return perfil; }
    public UUID getAlunoId() { return alunoId; }
    public boolean isAtivo() { return ativo; }
    public boolean isPrecisaTrocarSenha() { return precisaTrocarSenha; }
    public boolean isSomenteLeitura() { return somenteLeitura; }
    public int getVersaoToken() { return versaoToken; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getEmailConfirmadoEm() { return emailConfirmadoEm; }
}
