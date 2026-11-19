
package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.handler.AvisoVagaHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.api.handler.CadastroHandler;
import br.edu.puccampinas.consultafacil.api.handler.CancelamentoHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaCalendarioHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaLeituraHandler;
import br.edu.puccampinas.consultafacil.api.handler.FilaEsperaHandler;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.handler.HistoricoConsultaHandler;
import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
import br.edu.puccampinas.consultafacil.api.handler.StatusConsultaHandler;
import br.edu.puccampinas.consultafacil.api.handler.CentralNotificacoesHandler;
import br.edu.puccampinas.consultafacil.api.handler.VagasHandler;
import br.edu.puccampinas.consultafacil.api.middleware.Cors;

import br.edu.puccampinas.consultafacil.config.*;
import br.edu.puccampinas.consultafacil.repository.*;
import br.edu.puccampinas.consultafacil.security.*;
import br.edu.puccampinas.consultafacil.service.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;

public class App {
    public static void main(String[] args) throws IOException {

        AppConfig config = AppConfig.load();
        MongoConnection mongo = new MongoConnection(
            config.mongoUri(),
            config.mongoDatabase()
        );

        mongo.ping();
        System.out.println("Conectado ao Mongo: " + config.mongoDatabase());

        var db = mongo.database();

        // CRUD de pacientes (sua implementação)
        var pacienteRepo = new PacienteRepository.Mongo(db);
        var pacienteService = new PacienteService(
            pacienteRepo,
            new PacienteValidator()
        );
        var pacienteHandler = new PacienteHandler(pacienteService);

        // Agendas (develop)
        var medicos = new MedicoRepository(db);
        var especialidades = new EspecialidadeRepository.Mongo(db);
        var medicoHandler = CadastroHandler.medicos(new MedicoService(medicos, especialidades));
        var especialidadeHandler = CadastroHandler.especialidades(new EspecialidadeService(especialidades, medicos));
        var agendaRepo = new AgendaRepository(db);
        var consultaRepo = new ConsultaRepository(db);
        // Consultas atingidas por mudança de jornada ou bloqueio (#44)
        var consultasAfetadas = new ConsultasAfetadas(consultaRepo, new NomesRepository.Mongo(db));
        var medicoDoUsuario = new MedicoDoUsuarioRepository(db);
        var acessoAgenda = new AcessoAgenda(medicoDoUsuario);
        var agendaHandler = new AgendaHandler(
            new AgendaService(agendaRepo, medicos, consultasAfetadas, Clock.systemUTC()), acessoAgenda
        );

        // Bloqueios (develop)
        var bloqueioRepo = new BloqueioRepository(db);
        var bloqueioHandler = new BloqueioHandler(
            new BloqueioService(
                bloqueioRepo,
                agendaRepo,
                medicos,
                consultasAfetadas
            ), acessoAgenda
        );

        var relogio = Clock.systemUTC();
        var fila = new FilaEsperaRepository(db);
        var notificacoes = new NotificacaoRepository(db);
        var nomes = new NomesRepository.Mongo(db);
        var pacienteDoUsuario = new PacienteDoUsuarioRepository(db);
        // Disponibilidade (#20) e busca de vagas + agendamento (#21)
        var historicoRepo = new HistoricoRepository.Mongo(db);
        var historico = new HistoricoProcessor(consultaRepo, historicoRepo);
        // Fila de espera (#32): ao cancelar, avisa os interessados compatíveis e registra SINALIZACAO_FILA
        var sinalizacaoFila = new SinalizacaoFilaService(fila, notificacoes, consultaRepo, nomes, historico, relogio);
        // e, quando uma vaga sinalizada é agendada, atualiza a fila (ATENDIDA / volta a AGUARDANDO)
        var registroConsultas = new RegistroConsultaService(consultaRepo, historico, relogio,
            sinalizacaoFila::sinalizar, sinalizacaoFila::consultaAgendada);
        var disponibilidade = new DisponibilidadeService(agendaRepo, bloqueioRepo, consultaRepo, relogio);
        var agendamento = new AgendamentoService(disponibilidade, medicos, especialidades,
            pacienteRepo, pacienteDoUsuario, registroConsultas);
        var avisosVaga = new AvisoVagaHandler(new AvisoVagaService(notificacoes, fila, consultaRepo,
            pacienteDoUsuario, sinalizacaoFila, agendamento, relogio));

        // Cancelamento (#27). A fila é avisada pelo RegistroConsultaService (#32); o AposCancelamento não é usado.
        // Quem vê/mexe em qual consulta: paciente as próprias, médico as dele, secretaria todas (#27, #30)
        var acessoConsulta = new AcessoConsulta(
            pacienteDoUsuario::buscar, medicoDoUsuario::buscar);
        var cancelamentoHandler = new CancelamentoHandler(new CancelamentoService(consultaRepo,
            acessoConsulta, registroConsultas, AposCancelamento.NENHUM, relogio));
        var statusConsulta = new StatusConsultaService(consultaRepo, acessoConsulta, registroConsultas, relogio);
        var statusConsultaHandler = new StatusConsultaHandler(statusConsulta);
        var central = new CentralNotificacoesHandler(new CentralNotificacoesService(notificacoes, consultaRepo,
                pacienteDoUsuario::buscar, medicoDoUsuario::buscar, statusConsulta, relogio));
        // Fila de espera (#31): inscrição, consulta e saída
        var filaEsperaHandler = new FilaEsperaHandler(new FilaEsperaService(fila, especialidades,
            medicos, pacienteRepo, pacienteDoUsuario, nomes, relogio));
        // Listagem e detalhe de consultas (#30)
        var consultaLeituraHandler = new ConsultaLeituraHandler(new ConsultaLeituraService(
            consultaRepo, nomes, acessoConsulta, relogio));
        var historicoConsultaHandler = new HistoricoConsultaHandler(new HistoricoConsultaService(
            consultaRepo, historicoRepo, acessoConsulta, nomes));

        // Calendário operacional (#23): leitura das consultas do médico no período
        var consultaCalendarioHandler = new ConsultaCalendarioHandler(new ConsultaCalendarioService(
            consultaRepo, agendaRepo, medicos, nomes), acessoAgenda);

        // Login e sessão (#9)
        var sessoes = new Sessoes(Duration.ofMinutes(config.sessionTtlMinutes()));
        var autenticador = new Autenticador(sessoes);
        var authHandler = new AuthHandler(
            new AuthService(new UsuarioRepository.Mongo(db), new SenhaHasher(), sessoes, Clock.systemUTC()),
            autenticador
        );

        // Rotas e permissões por perfil (#10): a tabela fica em api/Rotas.java
        var cors = new Cors(config.corsAllowedOrigins());
        var autorizacao = new Autorizacao(autenticador);
        Router router = Rotas.router(cors, autorizacao, new Rotas.Handlers(
            new HealthHandler(), authHandler, agendaHandler, bloqueioHandler, pacienteHandler,
            new VagasHandler(agendamento), new ConsultaHandler(agendamento), consultaCalendarioHandler, avisosVaga, central));

        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.serverPort()), 0
        );

        server.createContext("/", router);

        // Inclui /pacientes/{id} para consulta e edição.
        server.createContext("/pacientes", Rotas.pacientes(cors, autorizacao, pacienteHandler));
        server.createContext("/medicos", Rotas.catalogo(cors, autorizacao, medicoHandler));
        server.createContext("/especialidades", Rotas.catalogo(cors, autorizacao, especialidadeHandler));
        // Listagem, detalhe, cancelamento, status e histórico; "/consultas" sem barra continua no router.
        server.createContext("/consultas/", Rotas.consultaPorId(cors, autorizacao,
            consultaLeituraHandler, cancelamentoHandler, statusConsultaHandler, historicoConsultaHandler));
        server.createContext("/fila-espera", Rotas.filaEspera(cors, autorizacao, filaEsperaHandler));

        // Uma thread virtual por requisição (Java 21): uma requisição lenta não trava as outras.
        // Seguro porque a Autorizacao guarda o usuário por requisição e as reservas são protegidas
        // pelos índices do banco e pelas travas de médico e paciente (ReservasConcorrenciaIntegrationTest).
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        var recuperacaoFila = Executors.newSingleThreadScheduledExecutor();
        recuperacaoFila.scheduleWithFixedDelay(sinalizacaoFila::recuperarPendentes, 0, 15, java.util.concurrent.TimeUnit.SECONDS);

        System.out.println(
            "Servidor rodando na porta " + config.serverPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            recuperacaoFila.shutdownNow();
            server.stop(0);
            mongo.close();
        }));
    }
}
