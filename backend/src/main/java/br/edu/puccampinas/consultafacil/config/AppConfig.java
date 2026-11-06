package br.edu.puccampinas.consultafacil.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.List;

public record AppConfig(String mongoUri, String mongoDatabase, int serverPort,
                        List<String> corsAllowedOrigins, int sessionTtlMinutes) {
    public static AppConfig load() {
        Dotenv env = Dotenv.configure().directory("..").ignoreIfMissing().load();
        return new AppConfig(
            env.get("MONGODB_URI", "mongodb://localhost:27017"),
            env.get("MONGODB_DATABASE", "consultafacil"),
            Integer.parseInt(env.get("SERVER_PORT", "8080")),
            List.of(env.get("CORS_ALLOWED_ORIGINS", "http://localhost:5173").split(",")),
            Integer.parseInt(env.get("SESSION_TTL_MINUTES", "60"))
        );
    }
}
