package br.com.academiagestao.treinos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.DadosInvalidosException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.OptimisticLock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ficha de treino de um aluno (ADR 0027): divisões (Treino A, B, C...), cada uma com os
 * exercícios em ordem. Uma atual por aluno; a encerrada fica no histórico e nunca muda.
 */
@Entity
@Table(name = "fichas")
class Ficha {

    static final int MAXIMO_DIVISOES = 7;
    static final int MAXIMO_EXERCICIOS_POR_DIVISAO = 20;
    static final int TAMANHO_MAXIMO_NOME_DIVISAO = 40;
    static final int MAXIMO_SERIES = 20;
    static final int TAMANHO_MAXIMO_REPETICOES = 20;
    static final int TAMANHO_MAXIMO_CARGA = 30;
    static final int MAXIMO_DESCANSO_SEGUNDOS = 600;
    static final int TAMANHO_MAXIMO_OBSERVACAO = 500;

    @Id
    private UUID id;

    @Column(name = "aluno_id", nullable = false, updatable = false)
    private UUID alunoId;

    // Vêm sempre junto (open-in-view está desligado); SUBSELECT carrega as divisões de várias
    // fichas (o histórico) numa consulta só. Fora da versão: o Hibernate subiria a versão já ao
    // gravar a ficha nova (o ETag do cadastro não serviria); quem marca a correção é corrigidaEm.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @OptimisticLock(excluded = true)
    @JoinColumn(name = "ficha_id", nullable = false, updatable = false)
    @OrderBy("posicao")
    private List<DivisaoDaFicha> divisoes = new ArrayList<>();

    @Column(name = "revisao_em")
    private LocalDate revisaoEm;

    @Column(name = "montada_em", nullable = false, updatable = false)
    private Instant montadaEm;

    // Id do usuário (instrutor ou ADMIN) que montou.
    @Column(name = "montada_por", nullable = false, updatable = false)
    private UUID montadaPor;

    // Última correção; vazio = nunca corrigida.
    @Column(name = "corrigida_em")
    private Instant corrigidaEm;

    @Column(name = "encerrada_em")
    private Instant encerradaEm;

    @Version
    @Column(nullable = false)
    private long versao;

    // Exigido pelo JPA. Protegido para ninguém criar uma Ficha vazia pelo código.
    protected Ficha() {
    }

    /**
     * @param nomes nome atual no catálogo de cada exercício citado; um exercício fora do mapa
     *              não existe (quem chama já recusou os inativos)
     */
    static Ficha montar(UUID alunoId, DadosFicha dados, Map<UUID, String> nomes, UUID montadaPor, Instant agora) {
        Ficha ficha = new Ficha();
        ficha.id = UUID.randomUUID();
        ficha.alunoId = alunoId;
        ficha.montadaPor = montadaPor;
        ficha.montadaEm = agora.truncatedTo(ChronoUnit.MICROS);
        ficha.aplicar(dados, nomes);
        return ficha;
    }

    /** Troca as divisões inteiras e a data de revisão. Só a ficha atual. */
    void corrigir(DadosFicha dados, Map<UUID, String> nomes, Instant agora) {
        garantirAtual();
        aplicar(dados, nomes);
        this.corrigidaEm = agora.truncatedTo(ChronoUnit.MICROS);
    }

    /** Vai para o histórico: daqui em diante, nunca muda. */
    void encerrar(Instant agora) {
        garantirAtual();
        this.encerradaEm = agora.truncatedTo(ChronoUnit.MICROS);
    }

    void garantirAtual() {
        if (!isAtual()) {
            throw new FichaRecusadaException("Ficha encerrada",
                    "A ficha encerrada fica no histórico e não muda; para outro treino, monte uma ficha nova");
        }
    }

    boolean isAtual() {
        return encerradaEm == null;
    }

    /** A data de revisão chegou (no próprio dia já conta). A ficha continua valendo. */
    boolean revisaoVencida(LocalDate hoje) {
        return isAtual() && revisaoEm != null && !revisaoEm.isAfter(hoje);
    }

    // Valida tudo antes de alterar qualquer campo: ou a ficha inteira muda, ou nada muda.
    private void aplicar(DadosFicha dados, Map<UUID, String> nomes) {
        if (dados == null) {
            throw new DadosInvalidosException("dados da ficha são obrigatórios");
        }
        LocalDate diaDaMontagem = Calendario.hoje(montadaEm);
        if (dados.revisaoEm() != null && !dados.revisaoEm().isAfter(diaDaMontagem)) {
            throw new DadosInvalidosException("revisão deve ser depois do dia em que a ficha foi montada ("
                    + diaDaMontagem + ")");
        }
        List<DadosFicha.Divisao> informadas = dados.divisoes();
        if (informadas == null || informadas.isEmpty()) {
            throw new DadosInvalidosException("a ficha precisa de pelo menos uma divisão");
        }
        if (informadas.size() > MAXIMO_DIVISOES) {
            throw new DadosInvalidosException("a ficha pode ter no máximo " + MAXIMO_DIVISOES + " divisões");
        }
        List<DivisaoDaFicha> novas = new ArrayList<>();
        Set<String> nomesDasDivisoes = new HashSet<>();
        for (int i = 0; i < informadas.size(); i++) {
            DivisaoDaFicha divisao = divisao(i + 1, informadas.get(i), nomes);
            if (!nomesDasDivisoes.add(divisao.getNome().toLowerCase(Locale.ROOT))) {
                throw new DadosInvalidosException("duas divisões com o nome \"" + divisao.getNome() + "\"");
            }
            novas.add(divisao);
        }

        this.revisaoEm = dados.revisaoEm();
        // A coleção é a mesma (o Hibernate a acompanha): as divisões antigas saem, as novas entram.
        this.divisoes.clear();
        this.divisoes.addAll(novas);
    }

    private static DivisaoDaFicha divisao(int posicao, DadosFicha.Divisao dados, Map<UUID, String> nomes) {
        String rotulo = "divisão " + posicao;
        if (dados == null) {
            throw new DadosInvalidosException(rotulo + ": dados obrigatórios");
        }
        String nome = Exercicio.limpar(dados.nome());
        if (nome.isEmpty()) {
            throw new DadosInvalidosException(rotulo + ": nome é obrigatório");
        }
        if (nome.length() > TAMANHO_MAXIMO_NOME_DIVISAO) {
            throw new DadosInvalidosException(rotulo + ": nome pode ter no máximo " + TAMANHO_MAXIMO_NOME_DIVISAO
                    + " caracteres");
        }
        List<DadosFicha.Item> itens = dados.exercicios();
        if (itens == null || itens.isEmpty()) {
            throw new DadosInvalidosException(rotulo + ": precisa de pelo menos um exercício");
        }
        if (itens.size() > MAXIMO_EXERCICIOS_POR_DIVISAO) {
            throw new DadosInvalidosException(rotulo + ": pode ter no máximo " + MAXIMO_EXERCICIOS_POR_DIVISAO
                    + " exercícios");
        }
        List<ExercicioDaFicha> exercicios = new ArrayList<>();
        for (int i = 0; i < itens.size(); i++) {
            exercicios.add(item(rotulo + ", exercício " + (i + 1), i + 1, itens.get(i), nomes));
        }
        return new DivisaoDaFicha(posicao, nome, exercicios);
    }

    private static ExercicioDaFicha item(String rotulo, int posicao, DadosFicha.Item dados, Map<UUID, String> nomes) {
        if (dados == null) {
            throw new DadosInvalidosException(rotulo + ": dados obrigatórios");
        }
        if (dados.exercicioId() == null) {
            throw new DadosInvalidosException(rotulo + ": exercício é obrigatório");
        }
        String nome = nomes.get(dados.exercicioId());
        if (nome == null) {
            throw new DadosInvalidosException(rotulo + ": exercício " + dados.exercicioId() + " não encontrado");
        }
        if (dados.series() == null || dados.series() < 1 || dados.series() > MAXIMO_SERIES) {
            throw new DadosInvalidosException(rotulo + ": séries deve ser de 1 a " + MAXIMO_SERIES);
        }
        String repeticoes = Exercicio.limpar(dados.repeticoes());
        if (repeticoes.isEmpty()) {
            throw new DadosInvalidosException(rotulo + ": repetições é obrigatório");
        }
        if (repeticoes.length() > TAMANHO_MAXIMO_REPETICOES) {
            throw new DadosInvalidosException(rotulo + ": repetições pode ter no máximo " + TAMANHO_MAXIMO_REPETICOES
                    + " caracteres");
        }
        String carga = Exercicio.limpar(dados.carga());
        if (carga.length() > TAMANHO_MAXIMO_CARGA) {
            throw new DadosInvalidosException(rotulo + ": carga pode ter no máximo " + TAMANHO_MAXIMO_CARGA
                    + " caracteres");
        }
        Integer descanso = dados.descansoSegundos();
        if (descanso != null && (descanso < 0 || descanso > MAXIMO_DESCANSO_SEGUNDOS)) {
            throw new DadosInvalidosException(rotulo + ": descanso deve ser de 0 a " + MAXIMO_DESCANSO_SEGUNDOS
                    + " segundos");
        }
        String observacao = dados.observacao() == null ? "" : dados.observacao().strip();
        if (observacao.length() > TAMANHO_MAXIMO_OBSERVACAO) {
            throw new DadosInvalidosException(rotulo + ": observação pode ter no máximo " + TAMANHO_MAXIMO_OBSERVACAO
                    + " caracteres");
        }
        return new ExercicioDaFicha(posicao, dados.exercicioId(), nome, dados.series(), repeticoes,
                carga.isEmpty() ? null : carga, descanso, observacao.isEmpty() ? null : observacao);
    }

    /** Para a trilha de auditoria: as divisões inteiras, como estavam. */
    Map<String, Object> retrato() {
        return Auditoria.campos("revisaoEm", revisaoEm,
                "divisoes", divisoes.stream().map(DivisaoDaFicha::retrato).toList(),
                "encerradaEm", encerradaEm);
    }

    UUID getId() { return id; }
    UUID getAlunoId() { return alunoId; }
    List<DivisaoDaFicha> getDivisoes() { return List.copyOf(divisoes); }
    LocalDate getRevisaoEm() { return revisaoEm; }
    Instant getMontadaEm() { return montadaEm; }
    UUID getMontadaPor() { return montadaPor; }
    Instant getCorrigidaEm() { return corrigidaEm; }
    Instant getEncerradaEm() { return encerradaEm; }
    long getVersao() { return versao; }
}
