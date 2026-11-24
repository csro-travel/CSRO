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
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Pattern CASE_READ_PATH = Pattern.compile("/api/v1/cases(?:/[^/]+(?:/(?:messages|quotes))?)?");
    private static final Pattern MESSAGES_PATH = Pattern.compile("/api/v1/cases/[^/]+/messages");
    private static final Pattern QUOTE_SEARCH_PATH = Pattern.compile("/api/v1/cases/[^/]+/quote-searches");
    private static final Pattern OFFERS_PATH = Pattern.compile("/api/v1/cases/[^/]+/offers");
    private static final Pattern SELECTION_PATH = Pattern.compile("/api/v1/cases/[^/]+/selection");

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
        String customerToken = environment.getProperty("CSRO_CUSTOMER_DEMO_TOKEN", "");
        if (password.isBlank() || token.length() < 32
                || token.equals(environment.getProperty("CSRO_DEMO_TOKEN"))
                || (!customerToken.isEmpty() && (customerToken.length() < 32 || customerToken.equals(token)
                    || customerToken.equals(environment.getProperty("CSRO_DEMO_TOKEN"))))) {
            throw new IllegalStateException("local-chat-demo requires a database password and a separate demo token");
        }
        var dataSource = new DriverManagerDataSource(DATABASE_URL, USER, password);
        verifyDatabaseIdentity(dataSource);
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.getBeanFactory().registerSingleton("localChatDemoAuthentication", filterRegistration(token, customerToken));
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

    private static FilterRegistrationBean<OncePerRequestFilter> filterRegistration(String token, String customerToken) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new DemoFilter(token, customerToken));
        registration.addUrlPatterns("/api/v1/cases/*");
        return registration;
    }

    static final class DemoFilter extends OncePerRequestFilter {
        private final byte[] authorization;
        private final byte[] customerAuthorization;

        DemoFilter(String token, String customerToken) {
            authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            customerAuthorization = customerToken.isEmpty() ? null : ("Bearer " + customerToken).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getServletPath();
            return switch (request.getMethod()) {
                case "GET" -> !CASE_READ_PATH.matcher(path).matches();
                case "POST" -> !MESSAGES_PATH.matcher(path).matches() && !QUOTE_SEARCH_PATH.matcher(path).matches()
                        && !OFFERS_PATH.matcher(path).matches();
                case "PUT" -> !SELECTION_PATH.matcher(path).matches();
                default -> true;
            };
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            Principal principal = null;
            if (ADDRESS.equals(request.getLocalAddr()) && ADDRESS.equals(request.getRemoteAddr()) && header != null) {
                byte[] supplied = header.getBytes(StandardCharsets.UTF_8);
                boolean customerPath = ("GET".equals(request.getMethod()) && MESSAGES_PATH.matcher(request.getServletPath()).matches())
                        || ("PUT".equals(request.getMethod()) && SELECTION_PATH.matcher(request.getServletPath()).matches());
                if (MessageDigest.isEqual(authorization, supplied)) principal = COUNSELOR;
                else if (customerPath && customerAuthorization != null && MessageDigest.isEqual(customerAuthorization, supplied))
                    principal = CUSTOMER;
            }
            if (principal != null) {
                Principal authenticated = principal;
                request = new HttpServletRequestWrapper(request) {
                    @Override
                    public Principal getUserPrincipal() {
                        return authenticated;
                    }
                };
            }
            chain.doFilter(request, response);
        }
    }
}
