package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.*;
import br.edu.puccampinas.consultafacil.service.*;
import br.edu.puccampinas.consultafacil.support.CadastrosEmMemoria;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CadastroServiceTest {
    private final CadastrosEmMemoria dados = new CadastrosEmMemoria();
    private final MedicoService medicos = new MedicoService(dados.medicoRepo, dados.especialidadeRepo);
    private final EspecialidadeService especialidades = new EspecialidadeService(dados.especialidadeRepo, dados.medicoRepo);

    private EspecialidadeResponse especialidade() {
        return especialidades.cadastrar(new EspecialidadeRequest("cardiologia", "Cardiologia", true));
    }

    private MedicoRequest medico(String especialidadeId, boolean ativo) {
        return new MedicoRequest("  Ana Teste  ", "000123", "SP", List.of(especialidadeId), ativo);
    }

    @Test void cadastraConsultaListaEditaEDesativaSemPerderVinculos() {
        var e = especialidade();
        var m = medicos.cadastrar(medico(e.id(), true));
        assertEquals("000123", m.crmNumero());
        assertEquals("Ana Teste", m.nome());
        assertEquals(List.of(e.id()), medicos.consultar(m.id()).especialidadeIds());
        assertEquals(List.of(m), medicos.listar());
        assertEquals(List.of(e), especialidades.listar());
        var editada = especialidades.atualizar(e.id(), new EspecialidadeRequest(e.codigo(), "Cardio", true));
        assertEquals("Cardio", editada.nome());
        assertEquals(e.codigo(), editada.codigo());
        medicos.atualizar(m.id(), new MedicoRequest("Ana Lima", m.crmNumero(), "SP", m.especialidadeIds(), false));
        assertFalse(medicos.consultar(m.id()).ativo());
        especialidades.atualizar(e.id(), new EspecialidadeRequest(e.codigo(), e.nome(), false));
        assertFalse(especialidades.consultar(e.id()).ativa());
        assertEquals(List.of(e.id()), medicos.consultar(m.id()).especialidadeIds());
        assertEquals(1, dados.medicos.size());
        assertEquals(1, dados.especialidades.size());
        // Reativação exige especialidades ativas de novo.
        status(400, () -> medicos.atualizar(m.id(), medico(e.id(), true)));
    }

    @Test void recusaDesativacaoDeEspecialidadeDeMedicoAtivo() {
        var e = especialidade();
        medicos.cadastrar(medico(e.id(), true));
        status(409, () -> especialidades.atualizar(e.id(), new EspecialidadeRequest(e.codigo(), e.nome(), false)));
        assertTrue(especialidades.consultar(e.id()).ativa());
    }

    @Test void recusaEspecialidadesVaziasRepetidasInexistentesOuInativas() {
        var e = especialidade();
        for (List<String> ids : List.of(List.<String>of(), List.of(e.id(), e.id()),
                List.of(new ObjectId().toHexString()), List.of("inválido"))) {
            status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", "000123", "SP", ids, true)));
        }
        status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", "000123", "SP", null, true)));
        especialidades.atualizar(e.id(), new EspecialidadeRequest(e.codigo(), e.nome(), false));
        status(400, () -> medicos.cadastrar(medico(e.id(), true)));
        assertTrue(dados.medicos.isEmpty());
    }

    @Test void normalizaIdsAntesDeDetectarRepeticao() {
        String id = new ObjectId().toHexString();
        dados.especialidades.put(id, new EspecialidadeResponse(id, "teste", "Teste", true));
        status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", "1", "SP",
                List.of(id, id.toUpperCase()), true)));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"12345678901", "12a", " 123", "-1"})
    void recusaCrmInvalido(String crm) {
        var e = especialidade();
        status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", crm, "SP", List.of(e.id()), true)));
    }

    @Test void validaLimitesNomeUfFlagsECodigo() {
        var e = especialidade();
        for (String nome : List.of("ab", " ", "a".repeat(121)))
            status(400, () -> medicos.cadastrar(new MedicoRequest(nome, "1", "SP", List.of(e.id()), true)));
        for (String uf : List.of("sp", "XX", "SPA", ""))
            status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", "1", uf, List.of(e.id()), true)));
        status(400, () -> medicos.cadastrar(new MedicoRequest("Ana Teste", "1", "SP", List.of(e.id()), null)));
        for (String codigo : List.of("Cardio", "cardio logia", ""))
            status(400, () -> especialidades.cadastrar(new EspecialidadeRequest(codigo, "Cardio", true)));
        status(400, () -> especialidades.cadastrar(new EspecialidadeRequest("valido", "a".repeat(101), true)));
        status(400, () -> especialidades.cadastrar(new EspecialidadeRequest("valido", " ", true)));
        status(400, () -> especialidades.cadastrar(new EspecialidadeRequest("valido", "Teste", null)));
    }

    @Test void diferenciaIdInvalidoERegistroInexistente() {
        status(400, () -> medicos.consultar("abc"));
        status(400, () -> especialidades.consultar("abc"));
        String id = new ObjectId().toHexString();
        status(404, () -> medicos.consultar(id));
        status(404, () -> especialidades.consultar(id));
        status(404, () -> medicos.atualizar(id, medico(id, true)));
        status(404, () -> especialidades.atualizar(id, new EspecialidadeRequest("teste", "Teste", true)));
    }

    private static void status(int esperado, Runnable acao) {
        assertEquals(esperado, assertThrows(ApiException.class, acao::run).status());
    }
}
