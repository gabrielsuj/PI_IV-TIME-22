package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta;
import br.edu.puccampinas.consultafacil.repository.ConsultaRepository;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.AcessoConsulta;
import br.edu.puccampinas.consultafacil.service.HistoricoProcessor;
import br.edu.puccampinas.consultafacil.service.RegistroConsultaService;
import br.edu.puccampinas.consultafacil.service.StatusConsultaService;
import br.edu.puccampinas.consultafacil.support.ConsultasEmMemoria;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static br.edu.puccampinas.consultafacil.domain.enums.StatusConsulta.*;
import static org.junit.jupiter.api.Assertions.*;

class StatusConsultaServiceTest {
    private static final Instant INICIO = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant FIM = INICIO.plusSeconds(1800);
    private static final ObjectId PACIENTE = new ObjectId(), MEDICO = new ObjectId();
    private static final UsuarioLogado PACIENTE_LOGADO = usuario(Perfil.PACIENTE);
    private static final UsuarioLogado MEDICO_LOGADO = usuario(Perfil.MEDICO);
    private static final UsuarioLogado SECRETARIA = usuario(Perfil.SECRETARIA_ADMIN);

    private final ConsultasEmMemoria consultas = new ConsultasEmMemoria();
    private final List<Document> eventos = new ArrayList<>();
    private boolean historicoIndisponivel;
    private final Clock relogio = Clock.fixed(FIM, ZoneOffset.UTC);
    private final HistoricoProcessor processor = new HistoricoProcessor(consultas, evento -> {
        if (historicoIndisponivel) throw new IllegalStateException("Histórico indisponível");
        eventos.add(evento);
    });
    private final List<ObjectId> cancelamentos = new ArrayList<>();
    private final RegistroConsultaService registro = new RegistroConsultaService(consultas, processor, relogio, cancelamentos::add);
    private final AcessoConsulta acesso = new AcessoConsulta(
            login -> Optional.ofNullable(PACIENTE_LOGADO.id().equals(login) ? PACIENTE : null),
            login -> Optional.ofNullable(MEDICO_LOGADO.id().equals(login) ? MEDICO : null));
    private final StatusConsultaService service = new StatusConsultaService(consultas, acesso, registro, relogio);

    private static UsuarioLogado usuario(Perfil perfil) {
        return new UsuarioLogado(new ObjectId().toHexString(), "Teste " + perfil, perfil);
    }

    private static UsuarioLogado ator(Perfil perfil) {
        return switch (perfil) {
            case PACIENTE -> PACIENTE_LOGADO;
            case MEDICO -> MEDICO_LOGADO;
            case SECRETARIA_ADMIN -> SECRETARIA;
        };
    }

    private ObjectId consulta(StatusConsulta status) {
        ObjectId id = new ObjectId();
        consultas.salvar(new Document("_id", id).append("pacienteId", PACIENTE).append("medicoId", MEDICO)
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(INICIO)).append("fim", Date.from(FIM))
                .append("status", status.name()).append("criadaEm", Date.from(INICIO.minusSeconds(86400))).append("versao", 0));
        return id;
    }

    static Stream<Arguments> transicoes() {
        Perfil[] perfis = {Perfil.PACIENTE, Perfil.MEDICO, Perfil.SECRETARIA_ADMIN};
        // Estado, destino, resultado de PACIENTE/MEDICO/SECRETARIA_ADMIN (0 = permitido).
        Object[][] matriz = {
                {AGENDADA, CONFIRMADA, 0, 0, 0},
                {AGENDADA, CONCLUIDA, 403, 409, 409},
                {AGENDADA, FALTA, 403, 0, 0},
                {CONFIRMADA, CONFIRMADA, 409, 409, 409},
                {CONFIRMADA, CONCLUIDA, 403, 0, 0},
                {CONFIRMADA, FALTA, 403, 0, 0},
                {CANCELADA, CONFIRMADA, 409, 409, 409},
                {CANCELADA, CONCLUIDA, 403, 409, 409},
                {CANCELADA, FALTA, 403, 409, 409},
                {CONCLUIDA, CONFIRMADA, 409, 409, 409},
                {CONCLUIDA, CONCLUIDA, 403, 409, 409},
                {CONCLUIDA, FALTA, 403, 409, 409},
                {FALTA, CONFIRMADA, 409, 409, 409},
                {FALTA, CONCLUIDA, 403, 409, 409},
                {FALTA, FALTA, 403, 409, 409},
        };
        return Arrays.stream(matriz).flatMap(linha -> IntStream.range(0, perfis.length)
                .mapToObj(i -> Arguments.of(perfis[i], linha[0], linha[1], linha[i + 2])));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2} (erro {3})")
    @MethodSource("transicoes")
    void respeitaMatrizDeEstadoEPerfil(Perfil perfil, StatusConsulta atual, StatusConsulta destino, int erro) {
        ObjectId id = consulta(atual);
        if (erro != 0) {
            assertEquals(erro, assertThrows(ApiException.class, () -> service.alterar(id, destino, ator(perfil))).status());
            assertEquals(atual.name(), consultas.docs.get(id).getString("status"));
            assertTrue(eventos.isEmpty());
            return;
        }
        var resposta = service.alterar(id, destino, ator(perfil));
        assertEquals(destino.name(), resposta.status());
        assertEquals(id.toHexString(), resposta.id());
        assertEquals(PACIENTE.toHexString(), resposta.pacienteId());
        assertEquals(MEDICO.toHexString(), resposta.medicoId());
        assertEquals(INICIO.toString(), resposta.inicio());
        assertEquals(FIM.toString(), resposta.fim());
        assertEquals(1, eventos.size());
        Document evento = eventos.getFirst();
        assertEquals(switch (destino) {
            case CONFIRMADA -> "CONFIRMACAO";
            case CONCLUIDA -> "CONCLUSAO";
            case FALTA -> "FALTA";
            default -> throw new AssertionError();
        }, evento.getString("evento"));
        assertEquals(atual.name(), evento.getString("statusAnterior"));
        assertEquals(destino.name(), evento.getString("statusNovo"));
        assertEquals(new ObjectId(ator(perfil).id()), evento.get("ator", Document.class).getObjectId("id"));
        assertEquals(perfil.name(), evento.get("ator", Document.class).getString("perfil"));
        assertEquals(Date.from(FIM), evento.getDate("ocorridoEm"));
        assertEquals(1, evento.getInteger("sequencia"));
        assertTrue(cancelamentos.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Perfil.class, names = {"PACIENTE", "MEDICO"})
    void recusaConsultaDeOutroCadastroMesmoComLoginValido(Perfil perfil) {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        consultas.docs.get(id).put(perfil == Perfil.PACIENTE ? "pacienteId" : "medicoId", new ObjectId());
        assertEquals(403, assertThrows(ApiException.class, () -> service.alterar(id, StatusConsulta.CONFIRMADA, ator(perfil))).status());
        assertTrue(eventos.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Perfil.class, names = {"PACIENTE", "MEDICO"})
    void loginSemVinculoNaoAlteraConsulta(Perfil perfil) {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        assertEquals(403, assertThrows(ApiException.class, () -> service.alterar(id, StatusConsulta.CONFIRMADA, usuario(perfil))).status());
        assertTrue(eventos.isEmpty());
    }

    @Test void exigeAtorValidoAntesDaEscrita() {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        for (UsuarioLogado invalido : Arrays.asList(null, new UsuarioLogado("inválido", "Teste", Perfil.SECRETARIA_ADMIN),
                new UsuarioLogado(null, "Teste", Perfil.SECRETARIA_ADMIN), new UsuarioLogado(SECRETARIA.id(), "Teste", null))) {
            assertEquals(401, assertThrows(ApiException.class, () -> service.alterar(id, StatusConsulta.CONFIRMADA, invalido)).status());
        }
        assertTrue(eventos.isEmpty());
    }

    @Test void distingueConsultaInexistenteDeIdAusente() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.alterar(null, StatusConsulta.CONFIRMADA, SECRETARIA)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.alterar(new ObjectId(), StatusConsulta.CONFIRMADA, SECRETARIA)).status());
    }

    @Test void naoAceitaAgendadaCancelamentoOuDestinoAusente() {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        for (StatusConsulta destino : Arrays.asList(null, StatusConsulta.AGENDADA, StatusConsulta.CANCELADA))
            assertEquals(400, assertThrows(ApiException.class, () -> service.alterar(id, destino, SECRETARIA)).status());
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
        assertTrue(eventos.isEmpty());
        assertTrue(cancelamentos.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = StatusConsulta.class, names = {"CONCLUIDA", "FALTA"})
    void recusaConclusaoOuFaltaAntesDoFimPrevisto(StatusConsulta destino) {
        ObjectId id = consulta(StatusConsulta.CONFIRMADA);
        for (Instant agora : List.of(INICIO.minusSeconds(1), INICIO, FIM.minusMillis(1))) {
            var antesDoFim = new StatusConsultaService(consultas, acesso, registro, Clock.fixed(agora, ZoneOffset.UTC));
            assertEquals(409, assertThrows(ApiException.class, () -> antesDoFim.alterar(id, destino, SECRETARIA)).status());
        }
        assertTrue(eventos.isEmpty());
    }

    @Test void permiteConfirmacaoAntesDoAtendimentoSemMudarAReserva() {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        var antecipada = new StatusConsultaService(consultas, acesso, registro, Clock.fixed(INICIO.minusSeconds(86400), ZoneOffset.UTC));
        assertEquals("CONFIRMADA", antecipada.alterar(id, StatusConsulta.CONFIRMADA, PACIENTE_LOGADO).status());
        assertEquals(Date.from(INICIO), consultas.docs.get(id).getDate("inicio"));
    }

    @Test void recusaMudancaEntreValidacaoDeAcessoEGravacao() {
        ObjectId id = consulta(StatusConsulta.AGENDADA);
        AcessoConsulta acessoConcorrente = new AcessoConsulta(login -> Optional.empty(), login -> Optional.of(MEDICO)) {
            @Override public void verificar(Document consulta, UsuarioLogado ator) {
                super.verificar(consulta, ator);
                // Outra operação transfere a consulta depois da verificação de propriedade.
                consultas.docs.get(id).put("medicoId", new ObjectId());
                consultas.docs.get(id).put("versao", 1);
            }
        };
        var concorrente = new StatusConsultaService(consultas, acessoConcorrente, registro, relogio);
        assertEquals(409, assertThrows(ApiException.class, () -> concorrente.alterar(id, StatusConsulta.CONFIRMADA, MEDICO_LOGADO)).status());
        assertEquals("AGENDADA", consultas.docs.get(id).getString("status"));
        assertTrue(eventos.isEmpty());
    }

    @Test void falhaDeProjecaoNaoDesfazStatusENaoDuplicaNaRepeticao() {
        ObjectId id = consulta(StatusConsulta.CONFIRMADA);
        historicoIndisponivel = true;
        assertEquals("CONCLUIDA", assertDoesNotThrow(() -> service.alterar(id, StatusConsulta.CONCLUIDA, SECRETARIA)).status());
        assertTrue(eventos.isEmpty());
        assertEquals(1, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
        assertEquals(409, assertThrows(ApiException.class, () -> service.alterar(id, StatusConsulta.CONCLUIDA, SECRETARIA)).status());
        historicoIndisponivel = false;
        processor.processarConsulta(id);
        assertEquals(1, eventos.size());
        assertEquals("CONCLUSAO", eventos.getFirst().getString("evento"));
    }

    @Test void tetoDePendenciasRecusaAntesDeAlterarStatus() {
        ObjectId id = consulta(StatusConsulta.CONFIRMADA);
        historicoIndisponivel = true;
        List<Document> pendentes = new ArrayList<>();
        for (int i = 1; i <= ConsultaRepository.LIMITE_PENDENCIAS; i++)
            pendentes.add(new Document("_id", new ObjectId()).append("sequencia", i));
        consultas.docs.get(id).put("eventosPendentes", pendentes);
        assertEquals(503, assertThrows(ApiException.class, () -> service.alterar(id, StatusConsulta.CONCLUIDA, SECRETARIA)).status());
        assertEquals("CONFIRMADA", consultas.docs.get(id).getString("status"));
        assertEquals(ConsultaRepository.LIMITE_PENDENCIAS, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
        assertTrue(eventos.isEmpty());
    }
}
