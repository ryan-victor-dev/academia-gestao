package br.com.academiagestao.alunos;

import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "alunos")
public class Aluno {

    static final int TAMANHO_MAXIMO_NOME = 150;
    static final int TAMANHO_MAXIMO_EMAIL = 150;
    /** Nascimento mais antigo aceito (decisão do dono, revisão de 2026-10-17): pega erro de digitação. */
    static final int IDADE_MAXIMA_ANOS = 120;

    @Id
    private UUID id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    // A coluna é CHAR(11). Sem o @JdbcTypeCode o Hibernate esperaria VARCHAR e a
    // validação do schema (ddl-auto: validate) impediria a aplicação de subir.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 11)
    private String cpf;

    @Column(length = TAMANHO_MAXIMO_EMAIL)
    private String email;

    @Column(length = 20)
    private String telefone;

    @Column(name = "data_nascimento")
    private LocalDate dataNascimento;

    @Column(columnDefinition = "text")
    private String observacoes;

    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    // Travamento otimista: o Hibernate inclui "WHERE versao = ?" no UPDATE e incrementa
    // o número. Se outra transação mudou o aluno antes, o UPDATE não acha a linha e falha.
    @Version
    @Column(nullable = false)
    private long versao;

    // Exigido pelo JPA. Protegido para ninguém criar um Aluno vazio pelo código.
    protected Aluno() {
    }

    /** @param agora momento do cadastro (do relógio da aplicação), de onde sai o "hoje" na academia. */
    public static Aluno novo(DadosAluno dados, Instant agora) {
        Aluno aluno = new Aluno();
        aluno.id = UUID.randomUUID();
        aluno.ativo = true;
        // O Postgres guarda microssegundos; sem truncar, a resposta do cadastro mostraria
        // nanossegundos e a consulta seguinte, um horário "diferente".
        aluno.criadoEm = agora.truncatedTo(ChronoUnit.MICROS);
        aluno.aplicar(dados, agora);
        return aluno;
    }

    /** Corrige ou completa o cadastro. Vale também para o CPF (digitado errado, por exemplo). */
    public void atualizar(DadosAluno dados, Instant agora) {
        aplicar(dados, agora);
    }

    /** O próprio aluno corrigiu o e-mail do login no primeiro acesso (ADR 0038): o cadastro acompanha. */
    public void corrigirEmail(String novoEmail) {
        String emailLimpo = textoOuNull(novoEmail);
        if (emailLimpo == null || !emailLimpo.contains("@") || emailLimpo.length() > TAMANHO_MAXIMO_EMAIL) {
            throw new DadosInvalidosException("e-mail inválido");
        }
        this.email = emailLimpo.toLowerCase(Locale.ROOT);
    }

    /** Aluno saiu da academia. Chamar de novo não muda nada. */
    public void inativar() {
        this.ativo = false;
    }

    /** Aluno voltou. Chamar de novo não muda nada. */
    public void reativar() {
        this.ativo = true;
    }

    // Valida tudo antes de alterar qualquer campo: ou o aluno inteiro muda, ou nada muda.
    private void aplicar(DadosAluno dados, Instant agora) {
        if (dados == null) {
            throw new DadosInvalidosException("dados do aluno são obrigatórios");
        }
        String nomeLimpo = textoOuNull(dados.nome());
        if (nomeLimpo == null) {
            throw new DadosInvalidosException("nome é obrigatório");
        }
        if (nomeLimpo.length() > TAMANHO_MAXIMO_NOME) {
            throw new DadosInvalidosException("nome pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres");
        }
        if (!Cpf.ehValido(dados.cpf())) {
            throw new DadosInvalidosException("CPF inválido");
        }
        String emailLimpo = textoOuNull(dados.email());
        if (emailLimpo != null && (!emailLimpo.contains("@") || emailLimpo.length() > TAMANHO_MAXIMO_EMAIL)) {
            throw new DadosInvalidosException("e-mail inválido");
        }
        String telefoneLimpo = normalizarTelefone(dados.telefone());
        if (telefoneLimpo != null && !telefoneLimpo.matches("\\d{10,11}")) {
            throw new DadosInvalidosException("telefone deve ter DDD + número (10 ou 11 dígitos)");
        }
        // No fuso da academia: o do servidor (UTC) aceitava, depois das 21h, nascer "amanhã".
        LocalDate hoje = Calendario.hoje(agora);
        if (dados.dataNascimento() != null && dados.dataNascimento().isAfter(hoje)) {
            throw new DadosInvalidosException("data de nascimento não pode estar no futuro");
        }
        if (dados.dataNascimento() != null && dados.dataNascimento().isBefore(hoje.minusYears(IDADE_MAXIMA_ANOS))) {
            throw new DadosInvalidosException("data de nascimento pode ser de no máximo " + IDADE_MAXIMA_ANOS
                    + " anos atrás");
        }

        this.nome = nomeLimpo;
        this.cpf = Cpf.normalizar(dados.cpf());
        this.email = emailLimpo == null ? null : emailLimpo.toLowerCase(Locale.ROOT);
        this.telefone = telefoneLimpo;
        this.dataNascimento = dados.dataNascimento();
        this.observacoes = textoOuNull(dados.observacoes());
    }

    private static String textoOuNull(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    /** "(31) 98765-4321" vira "31987654321". */
    private static String normalizarTelefone(String valor) {
        String texto = textoOuNull(valor);
        return texto == null ? null : texto.replaceAll("[\\s()\\-+.]", "");
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public String getCpf() { return cpf; }
    public String getEmail() { return email; }
    public String getTelefone() { return telefone; }
    public LocalDate getDataNascimento() { return dataNascimento; }
    public String getObservacoes() { return observacoes; }
    public boolean isAtivo() { return ativo; }
    public Instant getCriadoEm() { return criadoEm; }
    public long getVersao() { return versao; }
}
