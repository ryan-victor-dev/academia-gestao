package br.com.academiagestao.auth;

import br.com.academiagestao.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UsuarioRepositoryTest {

    @Autowired
    UsuarioRepository repository;

    @BeforeEach
    void limpar() {
        repository.deleteAll();
    }

    @Test
    void salvaEEncontraPeloEmail() {
        repository.saveAndFlush(Usuario.daEquipe("Ana", "ana@academia.com", "hash", Perfil.ADMIN));

        assertThat(repository.findByEmail("ana@academia.com"))
                .get()
                .extracting(Usuario::getPerfil)
                .isEqualTo(Perfil.ADMIN);
    }

    @Test
    void bancoRecusaEmailRepetidoMesmoComMaiusculas() {
        repository.saveAndFlush(Usuario.daEquipe("Ana", "ana@academia.com", "hash", Perfil.ADMIN));

        // A normalização no Java já evitaria isso; aqui provamos que o banco também protege.
        assertThatThrownBy(() -> repository.saveAndFlush(
                Usuario.daEquipe("Outra Ana", "ana@academia.com", "hash", Perfil.RECEPCAO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
