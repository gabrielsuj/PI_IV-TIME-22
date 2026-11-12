package br.edu.puccampinas.consultafacil.api;

import br.edu.puccampinas.consultafacil.api.middleware.Cors;
import br.edu.puccampinas.consultafacil.security.Autorizacao;
import com.sun.net.httpserver.HttpHandler;

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
 *  GET  /pacientes[/{id}]       |    -     |   ✓    |   ✓
 *  POST/PUT /pacientes[/{id}]   |    -     |   -    |   ✓
 *  GET /medicos, /especialidades[/{id}] | ✓ | ✓ | ✓
 *  POST/PUT desses cadastros    |    -     |   -    |   ✓
 */
public final class Rotas {
    private Rotas() {}

    public record Handlers(HttpHandler health, HttpHandler auth, HttpHandler agendas,
                           HttpHandler bloqueios, HttpHandler pacientes) {}

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
            .add("DELETE", "/bloqueios", autz.exigir(h.bloqueios(), MEDICO, SECRETARIA_ADMIN));
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
