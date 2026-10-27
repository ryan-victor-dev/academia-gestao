package br.com.academiagestao.pagamentos;

import br.com.academiagestao.compartilhado.Auditoria;
import br.com.academiagestao.compartilhado.VersaoEsperada;
import br.com.academiagestao.pagamentos.ConfiguracaoDoPagamentoOnline.Adiantar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static br.com.academiagestao.compartilhado.Auditoria.Entidade.CONFIGURACAO;

@Service
class ConfiguracaoDoPagamentoOnlineService {

    /** A trilha pede um id; a configuração é uma só. */
    static final UUID ID_NA_TRILHA = UUID.nameUUIDFromBytes(
            "configuracao-pagamento-online".getBytes(StandardCharsets.UTF_8));

    private final ConfiguracaoDoPagamentoOnlineRepository repository;
    private final Auditoria auditoria;

    ConfiguracaoDoPagamentoOnlineService(ConfiguracaoDoPagamentoOnlineRepository repository, Auditoria auditoria) {
        this.repository = repository;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    ConfiguracaoDoPagamentoOnline ler() {
        return repository.ler();
    }

    /** As escolhas só são conferidas depois do If-Match: com a versão errada, vale o 412 (ou 428). */
    @Transactional
    ConfiguracaoDoPagamentoOnline alterar(Escolhas escolhas, String ifMatch) {
        ConfiguracaoDoPagamentoOnline atual = repository.travar();
        VersaoEsperada.conferir(ifMatch, atual.versao(), "a configuração do pagamento online");
        ConfiguracaoDoPagamentoOnline gravada = repository.gravar(new ConfiguracaoDoPagamentoOnline(
                escolhas.pixLigado(), escolhas.boletoLigado(), escolhas.creditoLigado(), escolhas.debitoLigado(),
                escolhas.adiantar(), escolhas.diasDoBoleto(), atual.versao()));
        auditoria.registrar(CONFIGURACAO, ID_NA_TRILHA, "EDITADA", retrato(atual), retrato(gravada));
        return gravada;
    }

    private static Map<String, Object> retrato(ConfiguracaoDoPagamentoOnline c) {
        return Auditoria.campos("pixLigado", c.pixLigado(), "boletoLigado", c.boletoLigado(),
                "creditoLigado", c.creditoLigado(), "debitoLigado", c.debitoLigado(),
                "adiantar", c.adiantar().name(), "diasDoBoleto", c.diasDoBoleto());
    }

    record Escolhas(boolean pixLigado, boolean boletoLigado, boolean creditoLigado, boolean debitoLigado,
                    Adiantar adiantar, int diasDoBoleto) {
    }
}
