package br.com.academiagestao.auth;

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

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

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

    public void desativar() {
        this.ativo = false;
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }
    public String getSenhaHash() { return senhaHash; }
    public Perfil getPerfil() { return perfil; }
    public UUID getAlunoId() { return alunoId; }
    public boolean isAtivo() { return ativo; }
    public Instant getCriadoEm() { return criadoEm; }
}
