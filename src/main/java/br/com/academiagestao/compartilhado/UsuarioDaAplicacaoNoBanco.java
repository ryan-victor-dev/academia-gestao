package br.com.academiagestao.compartilhado;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Menor privilégio no banco. As migrations rodam com o dono do banco (que pode criar
 * tabelas); a aplicação, no dia a dia, usa outro usuário que só lê e grava dados.
 * Assim, mesmo uma falha de injeção de SQL não conseguiria apagar tabelas, criar
 * usuários ou mexer no servidor de banco.
 *
 * Roda depois de toda migração (inclusive quando não há nada novo) e garante o usuário,
 * a senha e as permissões, inclusive em tabelas criadas por migrations futuras.
 */
@Component
class UsuarioDaAplicacaoNoBanco implements Callback {

    // Só letras, números e "_": o nome entra no SQL sem aspas.
    private static final Pattern NOME_VALIDO = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    static final int TAMANHO_MINIMO_SENHA = 16;

    private final String usuario;
    private final String senha;

    UsuarioDaAplicacaoNoBanco(@Value("${spring.datasource.username}") String usuario,
                              @Value("${academia.banco.senha-app}") String senha) {
        if (!NOME_VALIDO.matcher(usuario).matches()) {
            throw new IllegalStateException("Nome de usuário do banco inválido: " + usuario);
        }
        if (senha == null || senha.length() < TAMANHO_MINIMO_SENHA) {
            throw new IllegalStateException("DB_APP_SENHA precisa ter pelo menos " + TAMANHO_MINIMO_SENHA
                    + " caracteres. Gere uma com: openssl rand -base64 24");
        }
        this.usuario = usuario;
        this.senha = senha;
    }

    @Override
    public boolean supports(Event evento, Context contexto) {
        return evento == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event evento, Context contexto) {
        return true;
    }

    @Override
    public void handle(Event evento, Context contexto) {
        // Quando o próprio dono do banco é o usuário da aplicação (ex.: nos testes), não há o que restringir.
        String dono;
        try (Statement sql = contexto.getConnection().createStatement()) {
            var rs = sql.executeQuery("SELECT current_user");
            rs.next();
            dono = rs.getString(1);
            if (dono.equals(usuario)) {
                return;
            }
            String senhaEscapada = senha.replace("'", "''");
            sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + usuario + "') "
                    + "THEN CREATE ROLE " + usuario + " LOGIN; END IF; END $$");
            sql.execute("ALTER ROLE " + usuario + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD '" + senhaEscapada + "'");
            sql.execute("GRANT USAGE ON SCHEMA public TO " + usuario);
            sql.execute("REVOKE CREATE ON SCHEMA public FROM " + usuario);
            sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + usuario);
            // O histórico de migrations é só do dono: a aplicação não lê nem altera.
            sql.execute("REVOKE ALL ON TABLE flyway_schema_history FROM " + usuario);
            // Cadastros não se apagam: matrícula é cancelada ou trocada, trancamento ganha a volta.
            // Sem o DELETE, nem uma injeção de SQL os apaga (revisão geral, B4). O cronograma de
            // cobranças da matrícula é gravado uma vez e só lido. Aluno, usuário e plano nunca
            // usados podem ser excluídos (ADR 0043): com histórico, a chave estrangeira barra o
            // DELETE, mesmo o de uma injeção.
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE matriculas, trancamentos FROM " + usuario);
            sql.execute("REVOKE TRUNCATE ON TABLE alunos, usuarios, planos FROM " + usuario);
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE cronogramas FROM " + usuario);
            // A trilha de auditoria só cresce: a aplicação insere e lê, mas não apaga nem
            // reescreve o histórico (nem com uma injeção de SQL).
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE auditoria FROM " + usuario);
            // Dinheiro não some: cobrança só muda de estado (cancelada, estornada), nunca é
            // apagada. A configuração é uma linha só, que a aplicação lê e altera.
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE cobrancas FROM " + usuario);
            // O registro de pagamentos só cresce: o estorno é um lançamento novo (ADR 0025).
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE lancamentos FROM " + usuario);
            sql.execute("REVOKE INSERT, DELETE, TRUNCATE ON TABLE configuracao_cobranca FROM " + usuario);
            // O caixa do dia não se apaga (só muda a situação, V35), e cada fechamento e cada
            // reabertura ficam no histórico (ADR 0031).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE caixas FROM " + usuario);
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE fechamentos_de_caixa, reaberturas_de_caixa FROM "
                    + usuario);
            // O histórico de treinos não se apaga (ADR 0027): a ficha encerrada fica, e o
            // exercício inativo continua nas fichas antigas. O exercício que nunca entrou numa
            // ficha pode ser excluído (ADR 0043): nas fichas, a chave estrangeira barra o DELETE. Divisões e exercícios da ficha
            // nunca se alteram: a correção apaga os da atual e grava novos (o gatilho da V32
            // recusa apagar os de uma encerrada).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE fichas FROM " + usuario);
            sql.execute("REVOKE TRUNCATE ON TABLE exercicios FROM " + usuario);
            sql.execute("REVOKE UPDATE, TRUNCATE ON TABLE ficha_divisoes, ficha_exercicios FROM " + usuario);
            // O Pix e o boleto gerados ficam, com o que aconteceu com eles (ADR 0034).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE pagamentos_online FROM " + usuario);
            sql.execute("REVOKE INSERT, DELETE, TRUNCATE ON TABLE configuracao_pagamento_online FROM " + usuario);
            // Os códigos mandados por e-mail ficam, encerrados (usados, trocados ou esgotados), ADR 0038.
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE codigos_de_email FROM " + usuario);
            // Identidade (ADR 0047): responder "nada" grava nulos; a linha só sai com o cadastro (chave estrangeira).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE identidade_dos_alunos, identidade_da_equipe FROM " + usuario);
            // Publicações (ADR 0047): a recepção e o ADMIN excluem, e a edição troca os links; TRUNCATE, nunca.
            sql.execute("REVOKE TRUNCATE ON TABLE publicacoes, publicacao_links FROM " + usuario);
            // Anexos: o anexo se exclui (e o envio interrompido é limpo), e os pedaços saem junto, em cascata; um
            // pedaço gravado nunca muda.
            sql.execute("REVOKE TRUNCATE ON TABLE publicacao_anexos FROM " + usuario);
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE anexo_partes FROM " + usuario);
            // Avisos de fatura (ADR 0047): o registro do que saiu fica (é o que impede o aviso repetido).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE avisos_de_fatura FROM " + usuario);
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE sms_simulados FROM " + usuario);
            // Avisos da academia: o que foi publicado e a fila ficam (a fila muda de situação, nunca sai); a preferência muda.
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE avisos_da_academia FROM " + usuario);
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE envios_da_academia, preferencias_de_avisos FROM " + usuario);
            // O registro de acessos também só cresce (V38, ADR 0031).
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE acessos FROM " + usuario);
            // A única saída da trilha e dos acessos: as funções do dono que só apagam o que
            // passou do prazo (V21, 5 anos; V38, 1 ano).
            sql.execute("GRANT EXECUTE ON FUNCTION apagar_auditoria_vencida(integer) TO " + usuario);
            sql.execute("GRANT EXECUTE ON FUNCTION apagar_acessos_vencidos(integer) TO " + usuario);
            sql.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + usuario);
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível preparar o usuário da aplicação no banco", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "usuario-da-aplicacao";
    }
}
