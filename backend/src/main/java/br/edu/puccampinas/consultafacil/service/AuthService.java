package br.edu.puccampinas.consultafacil.service;

import br.edu.puccampinas.consultafacil.api.ApiException;
import br.edu.puccampinas.consultafacil.api.dto.LoginRequest;
import br.edu.puccampinas.consultafacil.api.dto.LoginResponse;
import br.edu.puccampinas.consultafacil.domain.model.Usuario;
import br.edu.puccampinas.consultafacil.repository.UsuarioRepository;
import br.edu.puccampinas.consultafacil.security.SenhaHasher;
import br.edu.puccampinas.consultafacil.security.Sessoes;
import br.edu.puccampinas.consultafacil.security.UsuarioLogado;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/** Login e logout (#9). A verificação do token em cada requisição fica no Autenticador. */
public class AuthService {
    public static final String EMAIL_NAO_CADASTRADO = "E-mail não cadastrado.";
    public static final String SENHA_INCORRETA = "Senha incorreta.";
    public static final String USUARIO_INATIVO = "Usuário desativado. Procure a secretaria da clínica.";

    private final UsuarioRepository usuarios;
    private final SenhaHasher hasher;
    private final Sessoes sessoes;
    private final Clock relogio;

    public AuthService(UsuarioRepository usuarios, SenhaHasher hasher, Sessoes sessoes, Clock relogio) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.sessoes = sessoes;
        this.relogio = relogio;
    }

    public LoginResponse login(LoginRequest req) {
        if (req == null || vazio(req.email()) || vazio(req.senha()))
            throw ApiException.badRequest("Informe e-mail e senha.");
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        Optional<Usuario> encontrado = usuarios.buscarPorEmail(email);

        // Mensagens específicas por caso (decisão do time: clareza para o usuário).
        if (encontrado.isEmpty())
            throw ApiException.unauthorized(EMAIL_NAO_CADASTRADO);
        Usuario usuario = encontrado.get();

        if (!hasher.confere(req.senha(), usuario.senha()))
            throw ApiException.unauthorized(SENHA_INCORRETA);

        // Checado depois da senha: só quem sabe a senha descobre que a conta está desativada.
        if (!usuario.ativo())
            throw ApiException.unauthorized(USUARIO_INATIVO);

        return abrirSessao(usuario);
    }

    public void logout(String token) {
        sessoes.encerrar(token);
    }

    private LoginResponse abrirSessao(Usuario u) {
        usuarios.registrarAcesso(u.id(), relogio.instant());
        var sessao = sessoes.criar(new UsuarioLogado(u.id(), u.nome(), u.perfil()));
        return new LoginResponse(sessao.token(), sessao.expiraEm().toString(), sessao.usuario());
    }

    private static boolean vazio(String s) { return s == null || s.isBlank(); }
}
