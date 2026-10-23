package br.com.academiagestao.compartilhado;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trilha de auditoria: quem alterou o quê, quando, e o valor anterior. Fica aqui, e a
 * implementação no módulo auditoria, para qualquer módulo registrar sem depender dele
 * (o auth não pode depender de outros módulos).
 *
 * Registra na transação de quem chama: se a alteração for desfeita, o registro também é.
 * O autor é o usuário logado; sem login (linha de comando, inicialização), é o sistema.
 */
public interface Auditoria {

    enum Entidade { PLANO, ALUNO, USUARIO, COBRANCA, CONFIGURACAO, EXERCICIO, FICHA, CAIXA }

    /**
     * @param antes  os campos antes da alteração (vazio numa criação)
     * @param depois os campos depois; só os que mudaram são gravados. Nunca inclua senhas.
     */
    void registrar(Entidade entidade, UUID id, String acao, Map<String, ?> antes, Map<String, ?> depois);

    /** Retrato de uma entidade para a trilha: campos("nome", nome, "ativo", ativo). Aceita nulos. */
    static Map<String, Object> campos(Object... nomesEValores) {
        if (nomesEValores.length % 2 != 0) {
            throw new IllegalArgumentException("campos(...) recebe pares de nome e valor");
        }
        Map<String, Object> campos = new LinkedHashMap<>();
        for (int i = 0; i < nomesEValores.length; i += 2) {
            campos.put((String) nomesEValores[i], nomesEValores[i + 1]);
        }
        return campos;
    }
}
