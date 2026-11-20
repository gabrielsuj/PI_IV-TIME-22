package br.edu.puccampinas.consultafacil.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.List;

public record AppConfig(String mongoUri, String mongoDatabase, int serverPort,
                        List<String> corsAllowedOrigins, int sessionTtlMinutes, Notificacoes notificacoes) {

    /**
     * Lembretes e notificações simuladas (#34).
     * @param antecedenciaHoras quantas horas antes da consulta o lembrete é enviado (padrão 24)
     * @param intervaloSegundos de quanto em quanto tempo o agendador procura notificações vencidas (padrão 60)
     * @param emailTeste se true, cada notificação ganha uma cópia no canal EMAIL_TESTE, "enviada" para o log
     */
    public record Notificacoes(int antecedenciaHoras, int intervaloSegundos, boolean emailTeste) {
        public Notificacoes {
            if (antecedenciaHoras < 1) throw new IllegalArgumentException("NOTIFICACOES_ANTECEDENCIA_HORAS deve ser >= 1");
            if (intervaloSegundos < 1) throw new IllegalArgumentException("NOTIFICACOES_INTERVALO_SEGUNDOS deve ser >= 1");
        }
    }

    public static AppConfig load() {
        Dotenv env = Dotenv.configure().directory("..").ignoreIfMissing().load();
        return new AppConfig(
            env.get("MONGODB_URI", "mongodb://localhost:27017"),
            env.get("MONGODB_DATABASE", "consultafacil"),
            Integer.parseInt(env.get("SERVER_PORT", "8080")),
            List.of(env.get("CORS_ALLOWED_ORIGINS", "http://localhost:5173").split(",")),
            Integer.parseInt(env.get("SESSION_TTL_MINUTES", "60")),
            new Notificacoes(
                Integer.parseInt(env.get("NOTIFICACOES_ANTECEDENCIA_HORAS", "24")),
                Integer.parseInt(env.get("NOTIFICACOES_INTERVALO_SEGUNDOS", "60")),
                Boolean.parseBoolean(env.get("NOTIFICACOES_EMAIL_TESTE", "false")))
        );
    }
}
