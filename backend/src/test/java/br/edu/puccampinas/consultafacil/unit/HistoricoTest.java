package br.edu.puccampinas.consultafacil.unit;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;
import br.edu.puccampinas.consultafacil.service.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoricoTest {
    private final Memoria consultas = new Memoria();
    private final Map<ObjectId, Document> eventos = new LinkedHashMap<>();
    private boolean indisponivel;
    private final HistoricoRepository destino = e -> {
        if (indisponivel) throw new IllegalStateException("falha simulada");
        Document anterior = eventos.putIfAbsent(e.getObjectId("_id"), copia(e));
        if (anterior != null && !anterior.equals(e)) throw new IllegalStateException("conflito");
    };
    private final HistoricoProcessor processor = new HistoricoProcessor(consultas, destino);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private final RegistroConsultaService service = new RegistroConsultaService(consultas, processor, clock);
    private final UsuarioLogado secretaria = new UsuarioLogado(new ObjectId().toHexString(), "Teste", Perfil.SECRETARIA_ADMIN);

    private static Document dados() {
        return new Document("pacienteId", new ObjectId()).append("medicoId", new ObjectId())
                .append("especialidadeId", new ObjectId()).append("inicio", Date.from(Instant.parse("2026-12-01T12:00:00Z")))
                .append("fim", Date.from(Instant.parse("2026-12-01T12:30:00Z")));
    }
    private ObjectId criar() { return service.criar(dados(), secretaria).getObjectId("_id"); }
    private static Document copia(Document d) { return Document.parse(d.toJson()); }

    @Test void criaETransfereComAtorEHorarioDoServidor() {
        ObjectId id = criar();
        assertEquals(1, eventos.size());
        Document e = eventos.values().iterator().next();
        assertEquals(id, e.getObjectId("consultaId"));
        assertEquals("CRIACAO", e.getString("evento"));
        assertNull(e.get("statusAnterior"));
        assertEquals("AGENDADA", e.getString("statusNovo"));
        assertEquals(Date.from(clock.instant()), e.getDate("ocorridoEm"));
        assertEquals(new ObjectId(secretaria.id()), e.get("ator", Document.class).getObjectId("id"));
        assertTrue(consultas.docs.get(id).getList("eventosPendentes", Document.class).isEmpty());
    }

    @Test void falhaDeHistoricoNaoDesfazConsultaEOutroProcessadorRecupera() {
        indisponivel = true;
        ObjectId id = assertDoesNotThrow(this::criar);
        assertEquals(1, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
        assertTrue(eventos.isEmpty());
        indisponivel = false;
        var reiniciado = new HistoricoProcessor(consultas, destino);
        assertEquals(1, reiniciado.recuperarLote(null, 10).eventosProcessados());
        assertEquals(1, eventos.size());
    }

    @Test void falhaAposInserirAntesDeReconhecerNaoDuplica() {
        consultas.falhaRemocao = true;
        ObjectId id = criar();
        assertEquals(1, eventos.size());
        assertEquals(1, consultas.docs.get(id).getList("eventosPendentes", Document.class).size());
        consultas.falhaRemocao = false;
        processor.recuperarLote(null, 10);
        processor.recuperarLote(null, 10);
        assertEquals(1, eventos.size());
        assertTrue(consultas.docs.get(id).getList("eventosPendentes", Document.class).isEmpty());
    }

    @Test void registraConfirmacaoConclusaoCancelamentoEFalta() {
        for (String status : List.of("CONCLUIDA", "CANCELADA", "FALTA")) {
            ObjectId id = criar();
            service.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
            service.alterar(id, new Document("status", status), secretaria);
        }
        assertEquals(9, eventos.size());
        assertEquals(Set.of("CRIACAO", "CONFIRMACAO", "CONCLUSAO", "CANCELAMENTO", "FALTA"),
                new HashSet<>(eventos.values().stream().map(e -> e.getString("evento")).toList()));
    }

    @Test void registraValoresAntesEDepoisSemEventoParaNoOp() {
        ObjectId id = criar();
        ObjectId medicoAnterior = consultas.docs.get(id).getObjectId("medicoId");
        ObjectId novo = new ObjectId();
        service.alterar(id, new Document("medicoId", novo), secretaria);
        service.alterar(id, new Document("medicoId", novo), secretaria);
        assertEquals(2, eventos.size());
        Document e = eventos.values().stream().filter(x -> x.getInteger("sequencia") == 2).findFirst().orElseThrow();
        Document mudanca = e.getList("alteracoes", Document.class).getFirst();
        assertEquals(medicoAnterior, mudanca.get("valorAnterior"));
        assertEquals(novo, mudanca.get("valorNovo"));
        assertEquals("AGENDADA", e.get("statusAnterior"));
        assertEquals(e.get("statusAnterior"), e.get("statusNovo"));
    }

    @Test void documentoAntigoRecebePrimeiroEventoSemInventarCriacao() {
        ObjectId id = new ObjectId();
        consultas.docs.put(id, dados().append("_id", id).append("status", "AGENDADA"));
        service.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
        assertEquals(1, consultas.docs.get(id).getInteger("versao"));
        assertEquals("CONFIRMACAO", eventos.values().iterator().next().get("evento"));
    }

    @Test void exigeAtorRealENaoAceitaMetadadosDoCliente() {
        assertEquals(401, assertThrows(ApiException.class, () -> service.criar(dados(), null)).status());
        assertThrows(ApiException.class, () -> service.criar(dados().append("eventosPendentes", List.of()), secretaria));
        assertTrue(consultas.docs.isEmpty());
    }

    @Test void recusaStatusFinalTransicaoInvalidaEPerfilSemPermissao() {
        ObjectId id = criar();
        UsuarioLogado paciente = new UsuarioLogado(new ObjectId().toHexString(), "Paciente teste", Perfil.PACIENTE);
        assertEquals(403, assertThrows(ApiException.class, () -> service.alterar(id, new Document("status", "FALTA"), paciente)).status());
        service.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
        assertEquals(409, assertThrows(ApiException.class, () -> service.alterar(id, new Document("status", "AGENDADA"), secretaria)).status());
        service.alterar(id, new Document("status", "CANCELADA"), secretaria);
        assertThrows(ApiException.class, () -> service.alterar(id, new Document("status", "CONFIRMADA"), secretaria));
        assertEquals(3, eventos.size());
    }

    @Test void conflitoConcorrenteNaoGeraEventoFantasma() {
        ObjectId id = criar();
        consultas.conflito = true;
        assertEquals(409, assertThrows(ApiException.class, () -> service.alterar(id, new Document("status", "CONFIRMADA"), secretaria)).status());
        assertEquals("AGENDADA", consultas.docs.get(id).get("status"));
        assertEquals(1, eventos.size());
    }

    @Test void limitePreservaTodasPendenciasERecuperacaoLiberaNovasAlteracoes() {
        indisponivel = true;
        ObjectId id = criar();
        for (int i = 1; i < ConsultaRepository.LIMITE_PENDENCIAS; i++)
            service.alterar(id, new Document("medicoId", new ObjectId()), secretaria);
        Document antes = copia(consultas.docs.get(id));
        assertEquals(503, assertThrows(ApiException.class, () -> service.alterar(id, new Document("status", "CONFIRMADA"), secretaria)).status());
        assertEquals(antes, consultas.docs.get(id));
        indisponivel = false;
        service.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
        assertEquals(51, eventos.size());
        assertTrue(consultas.docs.get(id).getList("eventosPendentes", Document.class).isEmpty());
    }

    @Test void erroEmUmaConsultaNaoImpedeOutraNemMonopolizaPaginacao() {
        indisponivel = true;
        ObjectId primeiro = criar(), segundo = criar();
        ObjectId menor = primeiro.compareTo(segundo) < 0 ? primeiro : segundo;
        HistoricoProcessor parcial = new HistoricoProcessor(consultas, e -> {
            if (e.getObjectId("consultaId").equals(menor)) throw new IllegalStateException("inválido");
        });
        var lote1 = parcial.recuperarLote(null, 1);
        assertEquals(1, lote1.falhas());
        assertNotNull(lote1.proximoCursor());
        var lote2 = parcial.recuperarLote(lote1.proximoCursor(), 1);
        assertEquals(1, lote2.eventosProcessados());
        assertEquals(1, consultas.docs.get(menor).getList("eventosPendentes", Document.class).size());
    }

    @Test void falhaNaPersistenciaDaConsultaNaoPublicaHistorico() {
        consultas.falhaInsercao = true;
        assertThrows(IllegalStateException.class, this::criar);
        assertTrue(consultas.docs.isEmpty());
        assertTrue(eventos.isEmpty());
    }

    @Test void legadoContinuaSequenciaJaExistenteNoHistorico() {
        ObjectId id = new ObjectId();
        consultas.docs.put(id, dados().append("_id", id).append("status", "AGENDADA"));
        HistoricoRepository anterior = new HistoricoRepository() {
            public void registrar(Document e) { destino.registrar(e); }
            public int ultimaSequencia(ObjectId consultaId) { assertEquals(id, consultaId); return 7; }
        };
        var registro = new RegistroConsultaService(consultas, new HistoricoProcessor(consultas, anterior), clock);
        registro.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
        assertEquals(8, eventos.values().iterator().next().getInteger("sequencia"));
        assertEquals(8, consultas.docs.get(id).getInteger("versao"));
    }

    @Test void eventoGrandeNaoModificaDocumentoLegado() {
        ObjectId id = new ObjectId();
        Document legado = dados().append("_id", id).append("status", "AGENDADA")
                .append("medicoId", "x".repeat(RegistroConsultaService.LIMITE_BYTES_EVENTO));
        consultas.docs.put(id, legado);
        Document antes = copia(legado);
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.alterar(id, new Document("medicoId", new ObjectId()), secretaria)).status());
        assertEquals(antes, consultas.docs.get(id));
        assertTrue(eventos.isEmpty());
    }

    @Test void erroNoSegundoEventoMantemApenasPendenciasNaoReconhecidas() {
        indisponivel = true;
        ObjectId id = criar();
        service.alterar(id, new Document("status", "CONFIRMADA"), secretaria);
        HistoricoProcessor parcial = new HistoricoProcessor(consultas, e -> {
            if (e.getInteger("sequencia") == 2) throw new IllegalStateException("falha");
        });
        var resultado = parcial.recuperarLote(null, 10);
        assertEquals(1, resultado.eventosProcessados());
        assertEquals(1, resultado.falhas());
        assertEquals(2, consultas.docs.get(id).getList("eventosPendentes", Document.class).getFirst().getInteger("sequencia"));
    }

    private static final class Memoria implements PendenciasConsultaRepository {
        final Map<ObjectId, Document> docs = new TreeMap<>();
        boolean falhaRemocao, conflito, falhaInsercao;
        public Optional<Document> buscar(ObjectId id) { return Optional.ofNullable(docs.get(id)).map(HistoricoTest::copia); }
        public Document inserirComEvento(Document d, Document e) {
            if (falhaInsercao) throw new IllegalStateException("falha persistência");
            Document salvo = copia(d).append("versao", 1).append("eventosPendentes", new ArrayList<>(List.of(copia(e))));
            docs.put(d.getObjectId("_id"), salvo); return copia(salvo);
        }
        public boolean atualizarComEvento(ObjectId id, int versao, Document campos, Document evento) {
            Document d = docs.get(id);
            if (conflito || d == null || d.getInteger("versao", 0) != versao) return false;
            List<Document> fila = d.getList("eventosPendentes", Document.class, new ArrayList<>());
            if (fila.size() >= ConsultaRepository.LIMITE_PENDENCIAS) return false;
            d.putAll(campos); d.put("versao", evento.getInteger("sequencia")); fila.add(copia(evento)); d.put("eventosPendentes", fila); return true;
        }
        public List<Document> pendentesApos(ObjectId cursor, int limite) {
            return docs.values().stream().filter(d -> cursor == null || d.getObjectId("_id").compareTo(cursor) > 0)
                    .filter(d -> !d.getList("eventosPendentes", Document.class, List.of()).isEmpty())
                    .limit(limite).map(HistoricoTest::copia).toList();
        }
        public void removerPendencia(ObjectId consulta, ObjectId evento) {
            if (falhaRemocao) throw new IllegalStateException("falha reconhecimento");
            docs.get(consulta).getList("eventosPendentes", Document.class).removeIf(e -> e.getObjectId("_id").equals(evento));
        }
    }
}
