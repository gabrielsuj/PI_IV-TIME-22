package br.edu.puccampinas.consultafacil.security;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.domain.enums.Perfil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Autorização por perfil (#10), sempre verificada no servidor.
 * Envolve um handler: sem sessão válida → 401; perfil fora da lista → 403; senão segue para o handler,
 * que pode obter quem está chamando com {@link #usuario(HttpExchange)}.
 */
public class Autorizacao {
    private static final String ATRIBUTO_USUARIO = "consultafacil.usuario";

    private final Autenticador autenticador;

    public Autorizacao(Autenticador autenticador) { this.autenticador = autenticador; }

    public HttpHandler exigir(HttpHandler destino, Perfil... permitidos) {
        Set<Perfil> perfis = permitidos.length == 0 ? EnumSet.noneOf(Perfil.class) : EnumSet.copyOf(List.of(permitidos));
        return ex -> {
            UsuarioLogado usuario = autenticador.exigir(ex);   // 401
            if (!perfis.contains(usuario.perfil()))
                throw ApiException.forbidden("Seu perfil não tem acesso a esta função.");
            ex.setAttribute(ATRIBUTO_USUARIO, usuario);
            destino.handle(ex);
        };
    }

    /** Usuário autenticado da requisição. Só existe em rotas envolvidas por {@link #exigir}. */
    public static UsuarioLogado usuario(HttpExchange ex) {
        return (UsuarioLogado) ex.getAttribute(ATRIBUTO_USUARIO);
    }
}
