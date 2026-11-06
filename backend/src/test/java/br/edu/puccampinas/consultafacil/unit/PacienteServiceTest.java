package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.dto.PacienteRequest;
import br.edu.puccampinas.consultafacil.domain.exception.ValidacaoException;
import br.edu.puccampinas.consultafacil.domain.model.Paciente;
import br.edu.puccampinas.consultafacil.repository.PacienteRepository;
import br.edu.puccampinas.consultafacil.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class PacienteServiceTest {
    private final EmMemoria repository = new EmMemoria();
    private final PacienteService service = new PacienteService(repository, new PacienteValidator());
    private final PacienteRequest ana = new PacienteRequest("Ana", "52998224725", "ana@exemplo.com");

    @Test void cadastraConsultaELista() {
        var p = service.cadastrar(ana);
        assertEquals(p, service.consultar(p.id()).orElseThrow());
        assertEquals(List.of(p), service.listar(null));
        assertEquals(List.of(p), service.listar(" ANA "));
        assertTrue(service.listar("inexistente").isEmpty());
    }

    @Test void rejeitaCpfDuplicadoNormalizado() {
        service.cadastrar(ana);
        var e = assertThrows(ValidacaoException.class, () -> service.cadastrar(
                new PacienteRequest("Outra", "529.982.247-25", "outra@exemplo.com")));
        assertTrue(e.getMessage().contains("CPF"));
        assertEquals(1, service.listar("").size());
    }

    @Test void rejeitaEmailDuplicadoNormalizado() {
        service.cadastrar(ana);
        var e = assertThrows(ValidacaoException.class, () -> service.cadastrar(
                new PacienteRequest("Outra", "11144477735", " ANA@EXEMPLO.COM ")));
        assertTrue(e.getMessage().contains("E-mail"));
    }

    @Test void editaMantendoCpfEmailDoProprioPaciente() {
        var p = service.cadastrar(ana);
        var atualizado = service.atualizar(p.id().toUpperCase(Locale.ROOT),
                new PacienteRequest("Ana Silva", "529.982.247-25", "ANA@EXEMPLO.COM")).orElseThrow();
        assertEquals("Ana Silva", atualizado.nome());
        assertEquals(p.id(), atualizado.id());
        assertEquals(atualizado, service.consultar(p.id()).orElseThrow());
    }

    @Test void editaTrocandoCpfEmail() {
        var p = service.cadastrar(ana);
        var atualizado = service.atualizar(p.id(),
                new PacienteRequest("Ana", "11144477735", "novo@exemplo.com")).orElseThrow();
        assertEquals("11144477735", atualizado.cpf());
        assertEquals("novo@exemplo.com", atualizado.email());
    }

    @Test void rejeitaCpfEmailDeOutroNaEdicao() {
        service.cadastrar(ana);
        var outro = service.cadastrar(new PacienteRequest("Bia", "11144477735", "bia@exemplo.com"));
        assertThrows(ValidacaoException.class, () -> service.atualizar(outro.id(),
                new PacienteRequest("Bia", ana.cpf(), outro.email())));
        assertThrows(ValidacaoException.class, () -> service.atualizar(outro.id(),
                new PacienteRequest("Bia", outro.cpf(), ana.email())));
        assertEquals(outro, service.consultar(outro.id()).orElseThrow());
    }

    @Test void validaAntesDeGravar() {
        assertThrows(ValidacaoException.class, () -> service.cadastrar(new PacienteRequest("", ana.cpf(), ana.email())));
        assertTrue(repository.dados.isEmpty());
        var p = service.cadastrar(ana);
        assertThrows(ValidacaoException.class, () -> service.atualizar(p.id(), new PacienteRequest("Ana", null, ana.email())));
        assertEquals(p, service.consultar(p.id()).orElseThrow());
    }

    @Test void trataIdInvalidoEInexistente() {
        assertThrows(ValidacaoException.class, () -> service.consultar("xyz"));
        assertThrows(ValidacaoException.class, () -> service.atualizar("xyz", ana));
        assertTrue(service.consultar("000000000000000000000000").isEmpty());
        assertTrue(service.atualizar("000000000000000000000000", ana).isEmpty());
    }

    public static class EmMemoria implements PacienteRepository {
        public final Map<String, Paciente> dados = new LinkedHashMap<>();
        public Paciente cadastrar(Paciente p) { dados.put(p.id(), p); return p; }
        public Optional<Paciente> atualizar(Paciente p) {
            return dados.replace(p.id(), p) == null ? Optional.empty() : Optional.of(p);
        }
        public Optional<Paciente> consultar(String id) { return Optional.ofNullable(dados.get(id)); }
        public List<Paciente> listar(String busca) {
            String b = busca.toLowerCase(Locale.ROOT);
            String cpf = b.replace(".", "").replace("-", "");
            return dados.values().stream().filter(p -> p.nome().toLowerCase(Locale.ROOT).contains(b)
                    || p.email().contains(b) || p.cpf().contains(cpf)).toList();
        }
        public boolean cpfEmUso(String cpf, String excetoId) {
            return dados.values().stream().anyMatch(p -> p.cpf().equals(cpf) && !p.id().equals(excetoId));
        }
        public boolean emailEmUso(String email, String excetoId) {
            return dados.values().stream().anyMatch(p -> p.email().equals(email) && !p.id().equals(excetoId));
        }
    }
}
