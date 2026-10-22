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
            // A trilha de auditoria só cresce: a aplicação insere e lê, mas não apaga nem
            // reescreve o histórico (nem com uma injeção de SQL).
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE auditoria FROM " + usuario);
            // Dinheiro não some: cobrança só muda de estado (cancelada, estornada), nunca é
            // apagada. A configuração é uma linha só, que a aplicação lê e altera.
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE cobrancas FROM " + usuario);
            // O registro de pagamentos só cresce: o estorno é um lançamento novo (ADR 0025).
            sql.execute("REVOKE UPDATE, DELETE, TRUNCATE ON TABLE lancamentos FROM " + usuario);
            sql.execute("REVOKE INSERT, DELETE, TRUNCATE ON TABLE configuracao_cobranca FROM " + usuario);
            // O histórico de treinos não se apaga (ADR 0027): a ficha encerrada fica, e o
            // exercício inativo continua nas fichas antigas. Divisões e exercícios da ficha
            // nunca se alteram: a correção apaga os da atual e grava novos (o gatilho da V32
            // recusa apagar os de uma encerrada).
            sql.execute("REVOKE DELETE, TRUNCATE ON TABLE fichas, exercicios FROM " + usuario);
            sql.execute("REVOKE UPDATE, TRUNCATE ON TABLE ficha_divisoes, ficha_exercicios FROM " + usuario);
            // A única saída da trilha: a função do dono que só apaga o que passou do prazo (V21).
            sql.execute("GRANT EXECUTE ON FUNCTION apagar_auditoria_vencida(integer) TO " + usuario);
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
