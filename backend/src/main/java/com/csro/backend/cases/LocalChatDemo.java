package com.csro.backend.cases;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LocalChatDemo {
    private static final String PROFILE = "local-chat-demo";
    private static final String ADDRESS = "127.0.0.1";
    private static final String DATABASE = "yeohaengbom_chat_demo";
    private static final String USER = "yb_chat_demo";
    private static final String DATABASE_URL = "jdbc:postgresql://127.0.0.1:25434/" + DATABASE;
    private static final String FLYWAY_LOCATIONS = "classpath:db/migration,classpath:db/local";
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Pattern CASE_READ_PATH = Pattern.compile("/api/v1/cases(?:/[^/]+(?:/messages)?)?");
    private static final Pattern MESSAGES_PATH = Pattern.compile("/api/v1/cases/[^/]+/messages");

    private LocalChatDemo() {}

    /** 전용 DB를 먼저 확인한 뒤 Spring과 Flyway가 같은 DataSource를 사용하게 한다. */
    public static void configure(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if (!environment.matchesProfiles(PROFILE)) {
            return;
        }
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{PROFILE})
                || !ADDRESS.equals(environment.getProperty("server.address"))
                || !DATABASE_URL.equals(environment.getProperty("spring.datasource.url"))
                || !USER.equals(environment.getProperty("spring.datasource.username"))
                || !FLYWAY_LOCATIONS.equals(environment.getProperty("spring.flyway.locations"))
                || environment.containsProperty("spring.flyway.url")
                || environment.containsProperty("spring.flyway.user")
                || environment.containsProperty("spring.flyway.password")) {
            throw new IllegalStateException("local-chat-demo requires its exclusive profile and dedicated loopback database");
        }
        String password = environment.getProperty("CSRO_CHAT_DEMO_DB_PASSWORD", "");
        String token = environment.getProperty("CSRO_CHAT_DEMO_TOKEN", "");
        if (password.isBlank() || token.length() < 32
                || token.equals(environment.getProperty("CSRO_DEMO_TOKEN"))) {
            throw new IllegalStateException("local-chat-demo requires a database password and a separate demo token");
        }
        var dataSource = new DriverManagerDataSource(DATABASE_URL, USER, password);
        verifyDatabaseIdentity(dataSource);
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.getBeanFactory().registerSingleton("localChatDemoAuthentication", filterRegistration(token));
    }

    private static void verifyDatabaseIdentity(DriverManagerDataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2))) {
                throw new IllegalStateException("local-chat-demo database identity mismatch");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("local-chat-demo could not verify its dedicated PostgreSQL database");
        }
    }

    private static FilterRegistrationBean<OncePerRequestFilter> filterRegistration(String token) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new CounselorFilter(token));
        registration.addUrlPatterns("/api/v1/cases/*");
        return registration;
    }

    private static final class CounselorFilter extends OncePerRequestFilter {
        private final byte[] authorization;

        CounselorFilter(String token) {
            authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getServletPath();
            return switch (request.getMethod()) {
                case "GET" -> !CASE_READ_PATH.matcher(path).matches();
                case "POST" -> !MESSAGES_PATH.matcher(path).matches();
                default -> true;
            };
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (ADDRESS.equals(request.getLocalAddr()) && ADDRESS.equals(request.getRemoteAddr())
                    && header != null && MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.UTF_8))) {
                request = new HttpServletRequestWrapper(request) {
                    @Override
                    public Principal getUserPrincipal() {
                        return COUNSELOR;
                    }
                };
            }
            chain.doFilter(request, response);
        }
    }
}
