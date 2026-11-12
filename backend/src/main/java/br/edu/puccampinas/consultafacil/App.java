
package br.edu.puccampinas.consultafacil;

import br.edu.puccampinas.consultafacil.api.Rotas;
import br.edu.puccampinas.consultafacil.api.Router;
import br.edu.puccampinas.consultafacil.api.handler.AgendaHandler;
import br.edu.puccampinas.consultafacil.api.handler.AuthHandler;
import br.edu.puccampinas.consultafacil.api.handler.BloqueioHandler;
import br.edu.puccampinas.consultafacil.api.handler.CadastroHandler;
import br.edu.puccampinas.consultafacil.api.handler.ConsultaHandler;
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
        var agendaHandler = new AgendaHandler(
            new AgendaService(agendaRepo, medicos)
        );

        // Bloqueios (develop)
        var bloqueioRepo = new BloqueioRepository(db);
        var bloqueioHandler = new BloqueioHandler(
            new BloqueioService(
                bloqueioRepo,
                agendaRepo,
                medicos
            )
        );

        // Disponibilidade (#20) e busca de vagas + agendamento (#21)
        var consultaRepo = new ConsultaRepository(db);
        var historico = new HistoricoProcessor(consultaRepo, new HistoricoRepository.Mongo(db));
        var registroConsultas = new RegistroConsultaService(consultaRepo, historico, Clock.systemUTC());
        var disponibilidade = new DisponibilidadeService(agendaRepo, bloqueioRepo, consultaRepo, Clock.systemUTC());
        var agendamento = new AgendamentoService(disponibilidade, medicos, especialidades,
            pacienteRepo, new PacienteDoUsuarioRepository(db), registroConsultas);

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
            new VagasHandler(agendamento), new ConsultaHandler(agendamento)));

        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.serverPort()), 0
        );

        server.createContext("/", router);

        // Inclui /pacientes/{id} para consulta e edição.
        server.createContext("/pacientes", Rotas.pacientes(cors, autorizacao, pacienteHandler));
        server.createContext("/medicos", Rotas.catalogo(cors, autorizacao, medicoHandler));
        server.createContext("/especialidades", Rotas.catalogo(cors, autorizacao, especialidadeHandler));

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
