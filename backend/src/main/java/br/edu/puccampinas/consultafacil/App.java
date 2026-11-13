
package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.api.handler.CadastroHandler;
import br.edu.puccampinas.consultafacil.api.handler.CancelamentoHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaCalendarioHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaLeituraHandler;
import br.edu.puccampinas.consultafacil.api.handler.FilaEsperaHandler;
import br.edu.puccampinas.consultafacil.api.handler.HealthHandler;
import br.edu.puccampinas.consultafacil.api.handler.PacienteHandler;
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
        var agendaHandler = new AgendaHandler(
            new AgendaService(agendaRepo, medicos, consultasAfetadas, Clock.systemUTC())
        );

        // Bloqueios (develop)
        var bloqueioRepo = new BloqueioRepository(db);
        var bloqueioHandler = new BloqueioHandler(
            new BloqueioService(
                bloqueioRepo,
                agendaRepo,
                medicos,
                consultasAfetadas
            )
        );

        // Disponibilidade (#20) e busca de vagas + agendamento (#21)
        var historico = new HistoricoProcessor(consultaRepo, new HistoricoRepository.Mongo(db));
        // Fila de espera (#32): ao cancelar, avisa os interessados compatíveis e registra SINALIZACAO_FILA
        var sinalizacaoFila = new SinalizacaoFilaService(new FilaEsperaRepository(db), new NotificacaoRepository(db),
            consultaRepo, new NomesRepository.Mongo(db), historico, Clock.systemUTC());
        var registroConsultas = new RegistroConsultaService(consultaRepo, historico, Clock.systemUTC(), sinalizacaoFila::sinalizar);
        var disponibilidade = new DisponibilidadeService(agendaRepo, bloqueioRepo, consultaRepo, Clock.systemUTC());
        var agendamento = new AgendamentoService(disponibilidade, medicos, especialidades,
            pacienteRepo, new PacienteDoUsuarioRepository(db), registroConsultas);

        // Cancelamento (#27). A fila de espera (#32) troca o AposCancelamento.NENHUM pela implementação dela.
        // Quem vê/mexe em qual consulta: paciente as próprias, médico as dele, secretaria todas (#27, #30)
        var acessoConsulta = new AcessoConsulta(
            new PacienteDoUsuarioRepository(db)::buscar, new MedicoDoUsuarioRepository(db)::buscar);
        var cancelamentoHandler = new CancelamentoHandler(new CancelamentoService(consultaRepo,
            acessoConsulta, registroConsultas, AposCancelamento.NENHUM, Clock.systemUTC()));
        // Fila de espera (#31): inscrição, consulta e saída
        var filaEsperaHandler = new FilaEsperaHandler(new FilaEsperaService(new FilaEsperaRepository(db), especialidades,
            medicos, pacienteRepo, new PacienteDoUsuarioRepository(db), new NomesRepository.Mongo(db), Clock.systemUTC()));
        // Listagem e detalhe de consultas (#30)
        var consultaLeituraHandler = new ConsultaLeituraHandler(new ConsultaLeituraService(
            consultaRepo, new NomesRepository.Mongo(db), acessoConsulta, Clock.systemUTC()));

        // Calendário operacional (#23): leitura das consultas do médico no período
        var consultaCalendarioHandler = new ConsultaCalendarioHandler(new ConsultaCalendarioService(
            consultaRepo, agendaRepo, medicos, new NomesRepository.Mongo(db)));

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
            new VagasHandler(agendamento), new ConsultaHandler(agendamento), consultaCalendarioHandler));

        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.serverPort()), 0
        );

        server.createContext("/", router);

        // Inclui /pacientes/{id} para consulta e edição.
        server.createContext("/pacientes", Rotas.pacientes(cors, autorizacao, pacienteHandler));
        server.createContext("/medicos", Rotas.catalogo(cors, autorizacao, medicoHandler));
        server.createContext("/especialidades", Rotas.catalogo(cors, autorizacao, especialidadeHandler));
        // /consultas/lista, /consultas/{id} e /consultas/{id}/cancelamento (#27, #30); "/consultas" sem barra continua no router.
        server.createContext("/consultas/", Rotas.consultaPorId(cors, autorizacao, consultaLeituraHandler, cancelamentoHandler));
        server.createContext("/fila-espera", Rotas.filaEspera(cors, autorizacao, filaEsperaHandler));

        server.start();

        System.out.println(
            "Servidor rodando na porta " + config.serverPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            mongo.close();
        }));
    }
}
