package br.com.academiagestao.avisos;

import br.com.academiagestao.alunos.AlunosParaOutrosModulos;
import br.com.academiagestao.alunos.AlunosParaOutrosModulos.Contato;
import br.com.academiagestao.auth.ContatosDaEquipe;
import br.com.academiagestao.auth.ContatosDaEquipe.ContatoDaEquipe;
import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.MensageiroSms;
import br.com.academiagestao.compartilhado.PublicacaoExcluida;
import br.com.academiagestao.compartilhado.PublicacaoParaAvisar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Os avisos da academia (ADR 0047, decisão 8): o evento ou comunicado publicado com "avisar" vai por e-mail e SMS aos
 * alunos ativos e por e-mail à equipe ativa, menos a quem desligou.
 *
 * <p>Ao publicar, depois do commit, o que foi publicado é guardado e cada pessoa e canal entra na fila (a chave impede o
 * repetido). Uma tarefa manda de {@value #LOTE} em {@value #LOTE} a cada minuto, fora de transação (o envio vai para a
 * rede): milhares de alunos não seguram a publicação nem o banco. Na hora do envio, a preferência e o cadastro são lidos
 * de novo: quem desligou, saiu ou ficou sem contato nesse meio tempo não recebe. Excluir a publicação cancela o que ainda
 * não saiu. Falha no envio tenta de novo, até 3 vezes.
 */
@Component
class AvisosDaAcademia {

    static final int LOTE = 100;
    static final int TENTATIVAS = 3;

    private static final Logger log = LoggerFactory.getLogger(AvisosDaAcademia.class);

    private final AlunosParaOutrosModulos alunos;
    private final ContatosDaEquipe equipe;
    private final Correio correio;
    private final MensageiroSms sms;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate novaTransacao;
    private final Clock relogio;

    AvisosDaAcademia(AlunosParaOutrosModulos alunos, ContatosDaEquipe equipe, Correio correio, MensageiroSms sms,
                     JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio) {
        this.alunos = alunos;
        this.equipe = equipe;
        this.correio = correio;
        this.sms = sms;
        this.jdbc = jdbc;
        this.novaTransacao = new TransactionTemplate(transacoes);
        this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.relogio = relogio;
    }

    /** Depois do commit da publicação: se ela não foi gravada, ninguém é avisado. */
    @TransactionalEventListener
    void aoPublicar(PublicacaoParaAvisar p) {
        if (!correio.ligado() && !sms.ligado()) {
            log.info("Publicação {} com aviso, mas sem e-mail nem SMS configurados: ninguém é avisado", p.publicacaoId());
            return;
        }
        Instant agora = relogio.instant();
        Map<UUID, Contato> dosAlunos = alunos.contatosDosAtivos();
        List<ContatoDaEquipe> daEquipe = equipe.ativos();
        novaTransacao.executeWithoutResult(t -> {
            jdbc.update("""
                    INSERT INTO avisos_da_academia (publicacao_id, tipo, titulo, texto, evento_inicio, evento_fim, evento_local, criado_em)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", p.publicacaoId(), p.tipo(), p.titulo(), p.texto(), timestamp(p.eventoInicio()),
                    timestamp(p.eventoFim()), p.eventoLocal(), Timestamp.from(agora));
            Set<UUID> alunosDesligados = desligados("aluno_id");
            Set<UUID> equipeDesligada = desligados("usuario_id");
            List<Object[]> fila = new ArrayList<>();
            dosAlunos.forEach((id, c) -> {
                if (alunosDesligados.contains(id)) {
                    return;
                }
                if (correio.ligado() && c.email() != null) {
                    fila.add(new Object[] {p.publicacaoId(), id, null, "EMAIL", Timestamp.from(agora)});
                }
                if (sms.ligado() && MensageiroSms.ehCelular(c.telefone())) {
                    fila.add(new Object[] {p.publicacaoId(), id, null, "SMS", Timestamp.from(agora)});
                }
            });
            if (correio.ligado()) {
                daEquipe.stream().filter(c -> !equipeDesligada.contains(c.id()))
                        .forEach(c -> fila.add(new Object[] {p.publicacaoId(), null, c.id(), "EMAIL", Timestamp.from(agora)}));
            }
            jdbc.batchUpdate("""
                    INSERT INTO envios_da_academia (publicacao_id, aluno_id, usuario_id, canal, situacao, criado_em)
                    VALUES (?, ?, ?, ?, 'PENDENTE', ?)""", fila);
            log.info("Publicação {}: {} aviso(s) na fila", p.publicacaoId(), fila.size());
        });
    }

    @TransactionalEventListener
    void aoExcluir(PublicacaoExcluida p) {
        novaTransacao.executeWithoutResult(t -> jdbc.update("UPDATE envios_da_academia SET situacao = 'CANCELADO' "
                + "WHERE publicacao_id = ? AND situacao IN ('PENDENTE', 'FALHOU')", p.publicacaoId()));
    }

    @Scheduled(fixedDelayString = "${academia.avisos.intervalo-da-academia:PT1M}", initialDelayString = "${academia.avisos.primeira-vez:PT1M}")
    void enviarPendentes() {
        if (!correio.ligado() && !sms.ligado()) {
            return;
        }
        int enviados;
        do {
            enviados = enviarUmLote();
        } while (enviados == LOTE);
    }

    private record Envio(UUID publicacaoId, UUID alunoId, UUID usuarioId, String canal, int tentativas) {
    }

    /** @return quantos do lote foram tratados (enviados, cancelados ou falhos); menos que o lote, a fila acabou */
    int enviarUmLote() {
        List<Envio> lote = jdbc.query("""
                SELECT publicacao_id, aluno_id, usuario_id, canal, tentativas FROM envios_da_academia
                WHERE situacao IN ('PENDENTE', 'FALHOU') AND tentativas < ? ORDER BY criado_em LIMIT ?""",
                (rs, n) -> new Envio(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getInt(5)), TENTATIVAS, LOTE);
        if (lote.isEmpty()) {
            return 0;
        }
        Map<UUID, TextosDosAvisos.DaAcademia> avisos = new java.util.HashMap<>();
        jdbc.query("SELECT publicacao_id, tipo, titulo, texto, evento_inicio, evento_fim, evento_local FROM avisos_da_academia "
                + "WHERE publicacao_id = ANY (?)", rs -> {
                    avisos.put(rs.getObject(1, UUID.class), new TextosDosAvisos.DaAcademia(rs.getString(2), rs.getString(3),
                            rs.getString(4), instante(rs.getTimestamp(5)), instante(rs.getTimestamp(6)), rs.getString(7)));
                }, (Object) lote.stream().map(Envio::publicacaoId).distinct().toArray(UUID[]::new));
        Map<UUID, Contato> dosAlunos = alunos.contatos(lote.stream().map(Envio::alunoId).filter(java.util.Objects::nonNull).toList());
        Map<UUID, ContatoDaEquipe> daEquipe = equipe.ativosEntre(lote.stream().map(Envio::usuarioId).filter(java.util.Objects::nonNull)
                .toList()).stream().collect(Collectors.toMap(ContatoDaEquipe::id, Function.identity()));
        Set<UUID> alunosDesligados = desligados("aluno_id");
        Set<UUID> equipeDesligada = desligados("usuario_id");
        for (Envio envio : lote) {
            TextosDosAvisos.DaAcademia aviso = avisos.get(envio.publicacaoId());
            Runnable mandar = null;
            if (envio.alunoId() != null) {
                Contato c = dosAlunos.get(envio.alunoId());
                if (c != null && c.ativo() && !alunosDesligados.contains(envio.alunoId())) {
                    mandar = envio.canal().equals("SMS")
                            ? MensageiroSms.ehCelular(c.telefone()) && sms.ligado() ? () -> sms.enviar(c.telefone(), TextosDosAvisos.daAcademiaSms(aviso)) : null
                            : c.email() != null && correio.ligado() ? () -> email(c.email(), c.nome(), aviso) : null;
                }
            } else {
                ContatoDaEquipe c = daEquipe.get(envio.usuarioId());
                if (c != null && !equipeDesligada.contains(envio.usuarioId()) && correio.ligado()) {
                    mandar = () -> email(c.email(), c.nome(), aviso);
                }
            }
            if (mandar == null) {
                marcar(envio, "CANCELADO", envio.tentativas());
                continue;
            }
            try {
                mandar.run();
                marcar(envio, "ENVIADO", envio.tentativas() + 1);
            } catch (RuntimeException e) {
                log.warn("Aviso da publicação {} não saiu por {}: {}", envio.publicacaoId(), envio.canal(), e.getMessage());
                marcar(envio, "FALHOU", envio.tentativas() + 1);
            }
        }
        return lote.size();
    }

    private void email(String para, String nome, TextosDosAvisos.DaAcademia aviso) {
        TextosDosAvisos.Texto texto = TextosDosAvisos.daAcademia(aviso, nome);
        correio.enviar(new Correio.Mensagem(para, texto.assunto(), texto.corpo()));
    }

    private void marcar(Envio envio, String situacao, int tentativas) {
        jdbc.update("""
                UPDATE envios_da_academia SET situacao = ?, tentativas = ?, enviado_em = ?
                WHERE publicacao_id = ? AND aluno_id IS NOT DISTINCT FROM ? AND usuario_id IS NOT DISTINCT FROM ? AND canal = ?""",
                situacao, tentativas, situacao.equals("ENVIADO") ? Timestamp.from(relogio.instant()) : null,
                envio.publicacaoId(), envio.alunoId(), envio.usuarioId(), envio.canal());
    }

    private Set<UUID> desligados(String coluna) {
        return new HashSet<>(jdbc.queryForList("SELECT " + coluna + " FROM preferencias_de_avisos WHERE " + coluna
                + " IS NOT NULL AND NOT recebe_da_academia", UUID.class));
    }

    private static Timestamp timestamp(Instant instante) {
        return instante == null ? null : Timestamp.from(instante);
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
