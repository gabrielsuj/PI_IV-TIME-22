package br.edu.puccampinas.consultafacil.api.handler;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.Http;
import br.edu.puccampinas.consultafacil.api.dto.*;
import br.edu.puccampinas.consultafacil.service.EspecialidadeService;
import br.edu.puccampinas.consultafacil.service.MedicoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Rotas de coleção e por ID dos cadastros #13, fora do Router de caminhos exatos. */
public final class CadastroHandler<T, R> implements HttpHandler {
    private static final int MAX_BODY = 16384;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String rota;
    private final Set<String> campos;
    private final Function<JsonNode, T> ler;
    private final Supplier<?> listar;
    private final Function<String, R> consultar;
    private final Function<T, R> cadastrar;
    private final BiFunction<String, T, R> atualizar;
    private final Function<R, String> id;

    private CadastroHandler(String rota, Set<String> campos, Function<JsonNode, T> ler, Supplier<?> listar,
                            Function<String, R> consultar, Function<T, R> cadastrar,
                            BiFunction<String, T, R> atualizar, Function<R, String> id) {
        this.rota = rota;
        this.campos = campos;
        this.ler = ler;
        this.listar = listar;
        this.consultar = consultar;
        this.cadastrar = cadastrar;
        this.atualizar = atualizar;
        this.id = id;
    }

    public static HttpHandler medicos(MedicoService service) {
        return new CadastroHandler<>("/medicos", Set.of("nome", "crmNumero", "crmUf", "especialidadeIds", "ativo"),
                n -> new MedicoRequest(texto(n, "nome"), texto(n, "crmNumero"), texto(n, "crmUf"),
                        ids(n), booleano(n, "ativo")),
                service::listar, service::consultar, service::cadastrar, service::atualizar, MedicoResponse::id);
    }

    public static HttpHandler especialidades(EspecialidadeService service) {
        return new CadastroHandler<>("/especialidades", Set.of("codigo", "nome", "ativa"),
                n -> new EspecialidadeRequest(texto(n, "codigo"), texto(n, "nome"), booleano(n, "ativa")),
                service::listar, service::consultar, service::cadastrar, service::atualizar, EspecialidadeResponse::id);
    }

    @Override public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            boolean colecao = path.equals(rota) || path.equals(rota + "/");
            String registroId = path.startsWith(rota + "/") ? path.substring(rota.length() + 1) : "";
            if (!colecao && (registroId.isEmpty() || registroId.contains("/")))
                throw ApiException.notFound("Rota não encontrada");
            switch (ex.getRequestMethod()) {
                case "GET" -> Http.sendJson(ex, 200, colecao ? listar.get() : consultar.apply(registroId));
                case "POST" -> {
                    if (!colecao) { metodoInvalido(ex, false); return; }
                    R novo = cadastrar.apply(lerCorpo(ex));
                    ex.getResponseHeaders().set("Location", rota + "/" + id.apply(novo));
                    Http.sendJson(ex, 201, novo);
                }
                case "PUT" -> {
                    if (colecao) { metodoInvalido(ex, true); return; }
                    Http.sendJson(ex, 200, atualizar.apply(registroId, lerCorpo(ex)));
                }
                default -> metodoInvalido(ex, colecao);
            }
        } catch (ApiException e) {
            Http.sendError(ex, e.status(), e.getMessage());
        } catch (JsonProcessingException e) {
            Http.sendError(ex, 400, "JSON inválido. Envie um único objeto JSON");
        } catch (RuntimeException e) {
            Http.sendError(ex, 500, "Não foi possível processar a solicitação");
        }
        // O contexto de Rotas adiciona CORS e fecha a requisição.
    }

    private T lerCorpo(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw ApiException.badRequest("Dados excedem o tamanho permitido (16 KiB)");
        JsonNode node = json.readTree(body);
        if (node == null || !node.isObject()) throw ApiException.badRequest("Envie um objeto JSON");
        var nomes = node.fieldNames();
        while (nomes.hasNext()) {
            String nome = nomes.next();
            if (!campos.contains(nome)) throw ApiException.badRequest("Campo não permitido: " + nome);
        }
        return ler.apply(node);
    }

    private static String texto(JsonNode node, String campo) {
        JsonNode valor = node.get(campo);
        if (valor == null || valor.isNull()) return null;
        if (!valor.isTextual()) throw ApiException.badRequest(campo + " deve ser um texto");
        return valor.textValue();
    }

    private static Boolean booleano(JsonNode node, String campo) {
        JsonNode valor = node.get(campo);
        if (valor == null || !valor.isBoolean()) throw ApiException.badRequest(campo + " deve ser um booleano");
        return valor.booleanValue();
    }

    private static java.util.List<String> ids(JsonNode node) {
        JsonNode valores = node.get("especialidadeIds");
        if (valores == null || !valores.isArray()) throw ApiException.badRequest("especialidadeIds deve ser uma lista de IDs");
        var ids = new ArrayList<String>();
        for (JsonNode valor : valores) {
            if (!valor.isTextual()) throw ApiException.badRequest("Cada ID da especialidade deve ser um texto");
            ids.add(valor.textValue());
        }
        return ids;
    }

    private void metodoInvalido(HttpExchange ex, boolean colecao) throws IOException {
        ex.getResponseHeaders().set("Allow", colecao ? "GET, POST" : "GET, PUT");
        Http.sendError(ex, 405, "Método não permitido para esta rota");
    }
}
