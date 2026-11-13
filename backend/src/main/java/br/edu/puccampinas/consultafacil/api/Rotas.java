package br.edu.puccampinas.consultafacil.api;

import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import com.sun.net.httpserver.HttpHandler;

import java.util.regex.Pattern;

import static br.edu.puccampinas.consultafacil.domain.enums.Perfil.*;

/**
 * Tabela de rotas e de quem pode acessar cada uma (#10).
 * Fica separada do App para que a matriz de permissões seja testada sem banco (RotasAutorizacaoTest).
 *
 *  Rota                         | PACIENTE | MEDICO | SECRETARIA_ADMIN
 *  -----------------------------+----------+--------+-----------------
 *  GET  /health, /auth/*        | público (o /auth/me exige token)
 *  GET/PUT /agendas             |    -     |   ✓    |   ✓
 *  GET/POST/DELETE /bloqueios   |    -     |   ✓    |   ✓
 *  GET  /consultas (calendário) |    -     |   ✓    |   ✓
 *  GET  /pacientes[/{id}]       |    -     |   ✓    |   ✓
 *  POST/PUT /pacientes[/{id}]   |    -     |   -    |   ✓
 *  GET /medicos, /especialidades[/{id}] | ✓ | ✓ | ✓
 *  POST/PUT desses cadastros    |    -     |   -    |   ✓
 *  GET  /vagas (#21)            |    ✓     |   ✓    |   ✓
 *  POST /consultas (#21)        |    ✓     |   -    |   ✓   (paciente só para si mesmo)
 *  POST /consultas/{id}/cancelamento (#27) | ✓ | ✓  |   ✓   (paciente e médico só as próprias)
 *  GET  /consultas/lista, /consultas/{id} (#30) | ✓ | ✓ | ✓   (paciente e médico só as próprias)
 *  POST/GET /fila-espera, DELETE /fila-espera/{id} (#31) | ✓ | - | ✓ (paciente só as próprias)
 */
public final class Rotas {
    private Rotas() {}

    public record Handlers(HttpHandler health, HttpHandler auth, HttpHandler agendas,
                           HttpHandler bloqueios, HttpHandler pacientes,
                           HttpHandler vagas, HttpHandler consultas, HttpHandler calendario) {}

    public static Router router(Cors cors, Autorizacao autz, Handlers h) {
        return new Router(cors)
            .add("GET", "/health", h.health())
            .add("POST", "/auth/login", h.auth())
            .add("POST", "/auth/logout", h.auth())
            .add("GET", "/auth/me", h.auth())
            .add("GET", "/agendas", autz.exigir(h.agendas(), MEDICO, SECRETARIA_ADMIN))
            .add("PUT", "/agendas", autz.exigir(h.agendas(), MEDICO, SECRETARIA_ADMIN))
            .add("GET", "/bloqueios", autz.exigir(h.bloqueios(), MEDICO, SECRETARIA_ADMIN))
            .add("POST", "/bloqueios", autz.exigir(h.bloqueios(), MEDICO, SECRETARIA_ADMIN))
            .add("DELETE", "/bloqueios", autz.exigir(h.bloqueios(), MEDICO, SECRETARIA_ADMIN))
            .add("GET", "/vagas", autz.exigir(h.vagas(), PACIENTE, MEDICO, SECRETARIA_ADMIN))
            .add("POST", "/consultas", autz.exigir(h.consultas(), PACIENTE, SECRETARIA_ADMIN))
            .add("GET", "/consultas", autz.exigir(h.calendario(), MEDICO, SECRETARIA_ADMIN));
    }

    /**
     * /pacientes tem caminhos com id (/pacientes/{id}), que o Router (caminho exato) não atende,
     * por isso fica num contexto próprio. Leitura: médico e secretaria. Escrita: só secretaria.
     */
    public static HttpHandler pacientes(Cors cors, Autorizacao autz, HttpHandler pacientes) {
        HttpHandler leitura = autz.exigir(pacientes, MEDICO, SECRETARIA_ADMIN);
        HttpHandler escrita = autz.exigir(pacientes, SECRETARIA_ADMIN);
        return ex -> {
            try {
                cors.addHeaders(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                ("GET".equals(ex.getRequestMethod()) ? leitura : escrita).handle(ex);
            } catch (ApiException e) {
                Http.sendError(ex, e.status(), e.getMessage());
            } finally {
                ex.close();
            }
        };
    }

    /** /consultas/{id}/cancelamento: o {id} vem no caminho. Só o que vem depois da barra fica aqui. */
    private static final Pattern CANCELAMENTO = Pattern.compile("^/consultas/[^/]+/cancelamento$");
    /** /consultas/lista (#30) ou /consultas/{id} (#30): um segmento só depois de /consultas/. */
    private static final Pattern UM_SEGMENTO = Pattern.compile("^/consultas/[^/]+$");

    /**
     * Caminhos abaixo de "/consultas/" (contexto próprio). O GET/POST de "/consultas" sem barra
     * continua no Router, porque o servidor escolhe o contexto de prefixo mais longo.
     *  GET  /consultas/lista               → leitura (listagem, #30)
     *  GET  /consultas/{id}                → leitura (detalhe, #30)
     *  POST /consultas/{id}/cancelamento   → cancelamento (#27)
     */
    public static HttpHandler consultaPorId(Cors cors, Autorizacao autz, HttpHandler leitura, HttpHandler cancelamento) {
        HttpHandler ler = autz.exigir(leitura, PACIENTE, MEDICO, SECRETARIA_ADMIN);
        HttpHandler cancelar = autz.exigir(cancelamento, PACIENTE, MEDICO, SECRETARIA_ADMIN);
        return ex -> {
            try {
                cors.addHeaders(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                String metodo = ex.getRequestMethod();
                String caminho = ex.getRequestURI().getPath();
                if ("GET".equals(metodo) && UM_SEGMENTO.matcher(caminho).matches()) {
                    ler.handle(ex);
                    return;
                }
                if ("POST".equals(metodo) && CANCELAMENTO.matcher(caminho).matches()) {
                    cancelar.handle(ex);
                    return;
                }
                throw ApiException.notFound("Rota não encontrada");
            } catch (ApiException e) {
                Http.sendError(ex, e.status(), e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                Http.sendError(ex, 500, "Erro interno do servidor");
            } finally {
                ex.close();
            }
        };
    }

    /** /fila-espera e /fila-espera/{id} (#31). Contexto de prefixo: o padrão evita aceitar /fila-esperaX. */
    private static final Pattern FILA = Pattern.compile("^/fila-espera(/[^/]+)?$");

    /** Fila de espera (#31): paciente e secretaria em todos os métodos; o médico não usa a fila. */
    public static HttpHandler filaEspera(Cors cors, Autorizacao autz, HttpHandler fila) {
        HttpHandler autorizado = autz.exigir(fila, PACIENTE, SECRETARIA_ADMIN);
        return ex -> {
            try {
                cors.addHeaders(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                if (!FILA.matcher(ex.getRequestURI().getPath()).matches()) throw ApiException.notFound("Rota não encontrada");
                autorizado.handle(ex);
            } catch (ApiException e) {
                Http.sendError(ex, e.status(), e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                Http.sendError(ex, 500, "Erro interno do servidor");
            } finally {
                ex.close();
            }
        };
    }

    /** Leitura de catálogos para todos os perfis; manutenção exclusiva da secretaria/admin. */
    public static HttpHandler catalogo(Cors cors, Autorizacao autz, HttpHandler handler) {
        HttpHandler leitura = autz.exigir(handler, PACIENTE, MEDICO, SECRETARIA_ADMIN);
        HttpHandler escrita = autz.exigir(handler, SECRETARIA_ADMIN);
        return ex -> {
            try {
                cors.addHeaders(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                ("GET".equals(ex.getRequestMethod()) ? leitura : escrita).handle(ex);
            } catch (ApiException e) {
                Http.sendError(ex, e.status(), e.getMessage());
            } finally {
                ex.close();
            }
        };
    }
}
