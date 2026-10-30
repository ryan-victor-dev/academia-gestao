package br.com.academiagestao.avisos;

import br.com.academiagestao.alunos.AlunosParaOutrosModulos;
import br.com.academiagestao.alunos.AlunosParaOutrosModulos.Contato;
import br.com.academiagestao.cobranca.CobrancasParaAvisos;
import br.com.academiagestao.cobranca.CobrancasParaAvisos.FaturaEmAberto;
import br.com.academiagestao.compartilhado.Calendario;
import br.com.academiagestao.compartilhado.Correio;
import br.com.academiagestao.compartilhado.MensageiroSms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Os avisos de fatura (ADR 0047, decisão 7): a partir das 9h, no fuso da academia, cada fatura em aberto que cai num
 * marco hoje (5, 3 e 1 dia antes, no dia, 1, 3 e 5 dias de atraso) ganha um aviso por canal. Roda a cada 10 minutos:
 * se o servidor estava desligado às 9h, os do dia saem quando ele voltar.
 *
 * <p>Quem já pagou nunca recebe: antes de cada envio, uma transação trava a fatura para leitura, confere que ela
 * continua em aberto e reserva o aviso (fatura, marco e canal são a chave: o mesmo aviso nunca sai duas vezes). O envio
 * vem depois, fora da transação (ele vai para a rede). Um pagamento entre a reserva e o envio (frações de segundo) é o
 * único caso de aviso a quem acabou de pagar. Só alunos ativos recebem (quem saiu da academia trata com a recepção).
 */
@Component
class AvisosDeFatura {

    static final LocalTime HORA = LocalTime.of(9, 0);
    static final int TENTATIVAS = 3;

    enum Canal { EMAIL, SMS }

    private static final Logger log = LoggerFactory.getLogger(AvisosDeFatura.class);

    private final CobrancasParaAvisos cobrancas;
    private final AlunosParaOutrosModulos alunos;
    private final Correio correio;
    private final MensageiroSms sms;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    AvisosDeFatura(CobrancasParaAvisos cobrancas, AlunosParaOutrosModulos alunos, Correio correio, MensageiroSms sms,
                   JdbcTemplate jdbc, PlatformTransactionManager transacoes, Clock relogio) {
        this.cobrancas = cobrancas;
        this.alunos = alunos;
        this.correio = correio;
        this.sms = sms;
        this.jdbc = jdbc;
        this.transacao = new TransactionTemplate(transacoes);
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${academia.avisos.intervalo:PT10M}", initialDelayString = "${academia.avisos.primeira-vez:PT1M}")
    void executar() {
        Instant agora = relogio.instant();
        if (!correio.ligado() && !sms.ligado() || agora.atZone(Calendario.FUSO).toLocalTime().isBefore(HORA)) {
            return;
        }
        int enviados = enviarOsDeHoje(Calendario.hoje(agora));
        if (enviados > 0) {
            log.info("{} aviso(s) de fatura enviado(s)", enviados);
        }
    }

    /** @return quantos avisos saíram agora */
    int enviarOsDeHoje(LocalDate hoje) {
        List<FaturaEmAberto> faturas = cobrancas.emAbertoComPrazoEm(Arrays.stream(Marco.values()).map(m -> m.prazoPara(hoje)).toList());
        if (faturas.isEmpty()) {
            return 0;
        }
        Map<UUID, Contato> contatos = alunos.contatos(faturas.stream().map(FaturaEmAberto::alunoId).distinct().toList());
        CobrancasParaAvisos.RegrasDoAtraso r = cobrancas.regras();
        TextosDosAvisos.Regras regras = new TextosDosAvisos.Regras(r.acrescimoPercentual(), r.acrescimo(), r.jurosAoMes(),
                r.diasParaBloquear());
        int enviados = 0;
        for (FaturaEmAberto fatura : faturas) {
            Optional<Marco> marco = Marco.de(fatura.prazo(), hoje);
            Contato contato = contatos.get(fatura.alunoId());
            if (marco.isEmpty() || contato == null || !contato.ativo()) {
                continue;
            }
            TextosDosAvisos.Fatura dados = new TextosDosAvisos.Fatura(contato.nome(), fatura.tipo(), fatura.parcela(),
                    fatura.prazo(), fatura.valor(), fatura.devidoHoje(), fatura.diasDeAtraso());
            if (correio.ligado() && contato.email() != null && enviar(fatura.id(), marco.get(), Canal.EMAIL, () -> {
                TextosDosAvisos.Texto texto = TextosDosAvisos.email(marco.get(), dados, regras);
                correio.enviar(new Correio.Mensagem(contato.email(), texto.assunto(), texto.corpo()));
            })) {
                enviados++;
            }
            if (sms.ligado() && MensageiroSms.ehCelular(contato.telefone()) && enviar(fatura.id(), marco.get(), Canal.SMS,
                    () -> sms.enviar(contato.telefone(), TextosDosAvisos.sms(marco.get(), dados, regras)))) {
                enviados++;
            }
        }
        return enviados;
    }

    /** Reserva, envia e marca. @return se saiu agora */
    private boolean enviar(UUID faturaId, Marco marco, Canal canal, Runnable envio) {
        if (!reservar(faturaId, marco, canal)) {
            return false;
        }
        try {
            envio.run();
            marcar(faturaId, marco, canal, "ENVIADO");
            return true;
        } catch (RuntimeException e) {
            // O motivo fica no log, sem o endereço nem o número; a próxima rodada tenta de novo (até 3 vezes no dia).
            log.warn("Aviso de fatura {} ({}) não saiu por {}: {}", faturaId, marco, canal, e.getMessage());
            marcar(faturaId, marco, canal, "FALHOU");
            return false;
        }
    }

    /**
     * Confere, com a fatura travada, que ela continua em aberto, e reserva o aviso. Já reservado ou enviado, não; o que
     * falhou volta a valer, até a terceira tentativa. @return se este envio deve acontecer
     */
    private boolean reservar(UUID faturaId, Marco marco, Canal canal) {
        return Boolean.TRUE.equals(transacao.execute(t -> cobrancas.continuaEmAberto(faturaId) && jdbc.update("""
                INSERT INTO avisos_de_fatura (cobranca_id, marco, canal, situacao, reservado_em) VALUES (?, ?, ?, 'RESERVADO', ?)
                ON CONFLICT (cobranca_id, marco, canal) DO UPDATE SET situacao = 'RESERVADO', reservado_em = EXCLUDED.reservado_em,
                    tentativas = avisos_de_fatura.tentativas + 1
                WHERE avisos_de_fatura.situacao = 'FALHOU' AND avisos_de_fatura.tentativas < ?""",
                faturaId, marco.name(), canal.name(), Timestamp.from(relogio.instant()), TENTATIVAS) == 1));
    }

    private void marcar(UUID faturaId, Marco marco, Canal canal, String situacao) {
        jdbc.update("UPDATE avisos_de_fatura SET situacao = ?, enviado_em = ? WHERE cobranca_id = ? AND marco = ? AND canal = ?",
                situacao, situacao.equals("ENVIADO") ? Timestamp.from(relogio.instant()) : null, faturaId, marco.name(), canal.name());
    }
}
