package br.com.academiagestao.alunos;

import br.com.academiagestao.auth.PerfisDaEquipe;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.Identidade;
import br.com.academiagestao.compartilhado.Identidade.IdentidadeDeGenero;
import br.com.academiagestao.compartilhado.Identidade.OrientacaoSexual;
import br.com.academiagestao.compartilhado.Nascimento;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * O relatório de idades e gênero de alunos e equipe (ADR 0048, decisão 4): só o ADMIN abre. Para cada grupo, quantos
 * há em cada faixa etária, identidade de gênero e orientação sexual (com quem não respondeu à parte); e a lista de
 * todos. A idade é a de hoje, no fuso da academia.
 */
@Service
class RelatorioDePerfilService {

    /** Quem não respondeu (ou é de antes da pergunta). */
    static final String NAO_RESPONDIDO = "NAO_RESPONDIDO";

    enum Grupo { ALUNO, EQUIPE }

    /** {@code perfil}: o da equipe; nulo no aluno. */
    record Pessoa(Grupo grupo, UUID id, String nome, String perfil, boolean ativo, LocalDate dataNascimento, Integer idade,
                  FaixaEtaria faixa, IdentidadeDeGenero identidadeDeGenero, String identidadeDeGeneroOutra,
                  OrientacaoSexual orientacaoSexual, String orientacaoSexualOutra) {
    }

    /** As contagens, com todas as opções (as sem ninguém, com zero), na ordem das telas. */
    record Resumo(int total, Map<String, Integer> porFaixa, Map<String, Integer> porIdentidadeDeGenero,
                  Map<String, Integer> porOrientacaoSexual) {
    }

    record Relatorio(LocalDate dia, boolean comInativos, Resumo alunos, Resumo equipe, List<Pessoa> pessoas) {
    }

    private final JdbcTemplate jdbc;
    private final PerfisDaEquipe equipe;
    private final Clock relogio;

    RelatorioDePerfilService(JdbcTemplate jdbc, PerfisDaEquipe equipe, Clock relogio) {
        this.jdbc = jdbc;
        this.equipe = equipe;
        this.relogio = relogio;
    }

    @Transactional(readOnly = true)
    Relatorio gerar(boolean comInativos) {
        LocalDate hoje = Calendario.hoje(relogio.instant());
        List<Pessoa> alunos = jdbc.query("""
                SELECT a.id, a.nome, a.ativo, a.data_nascimento, i.identidade_de_genero, i.identidade_de_genero_outra,
                       i.orientacao_sexual, i.orientacao_sexual_outra
                FROM alunos a LEFT JOIN identidade_dos_alunos i ON i.aluno_id = a.id
                WHERE (? OR a.ativo)
                ORDER BY a.nome, a.id""", (rs, n) -> {
            String genero = rs.getString("identidade_de_genero");
            String orientacao = rs.getString("orientacao_sexual");
            return pessoa(Grupo.ALUNO, rs.getObject("id", UUID.class), rs.getString("nome"), null, rs.getBoolean("ativo"),
                    rs.getObject("data_nascimento", LocalDate.class),
                    new Identidade(genero == null ? null : IdentidadeDeGenero.valueOf(genero), rs.getString("identidade_de_genero_outra"),
                            orientacao == null ? null : OrientacaoSexual.valueOf(orientacao), rs.getString("orientacao_sexual_outra")),
                    hoje);
        }, comInativos);
        List<Pessoa> daEquipe = equipe.todos(comInativos).stream()
                .map(p -> pessoa(Grupo.EQUIPE, p.id(), p.nome(), p.perfil(), p.ativo(), p.dataNascimento(), p.identidade(), hoje))
                .toList();
        List<Pessoa> todos = new ArrayList<>(alunos);
        todos.addAll(daEquipe);
        return new Relatorio(hoje, comInativos, resumo(alunos), resumo(daEquipe), todos);
    }

    private static Pessoa pessoa(Grupo grupo, UUID id, String nome, String perfil, boolean ativo, LocalDate nascimento,
                                 Identidade identidade, LocalDate hoje) {
        Integer idade = nascimento == null ? null : Nascimento.idade(nascimento, hoje);
        return new Pessoa(grupo, id, nome, perfil, ativo, nascimento, idade, FaixaEtaria.de(idade),
                identidade.identidadeDeGenero(), identidade.identidadeDeGeneroOutra(), identidade.orientacaoSexual(),
                identidade.orientacaoSexualOutra());
    }

    private static Resumo resumo(List<Pessoa> pessoas) {
        return new Resumo(pessoas.size(),
                contar(pessoas, Arrays.stream(FaixaEtaria.values()).map(Enum::name).toList(), p -> p.faixa().name()),
                contar(pessoas, opcoes(IdentidadeDeGenero.values()), p -> nomeOu(p.identidadeDeGenero())),
                contar(pessoas, opcoes(OrientacaoSexual.values()), p -> nomeOu(p.orientacaoSexual())));
    }

    private static List<String> opcoes(Enum<?>[] valores) {
        List<String> nomes = new ArrayList<>(Arrays.stream(valores).map(Enum::name).toList());
        nomes.add(NAO_RESPONDIDO);
        return nomes;
    }

    private static String nomeOu(Enum<?> valor) {
        return valor == null ? NAO_RESPONDIDO : valor.name();
    }

    private static Map<String, Integer> contar(List<Pessoa> pessoas, List<String> chaves, Function<Pessoa, String> chave) {
        Map<String, Integer> contagem = new LinkedHashMap<>();
        chaves.forEach(c -> contagem.put(c, 0));
        pessoas.forEach(p -> contagem.merge(chave.apply(p), 1, Integer::sum));
        return contagem;
    }
}
