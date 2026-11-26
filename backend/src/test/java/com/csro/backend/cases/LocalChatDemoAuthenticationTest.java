package com.csro.backend.cases;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalChatDemoAuthenticationTest {
    private final LocalChatDemo.DemoFilter filter = new LocalChatDemo.DemoFilter("counselor-test-token", "customer-test-token");

    @Test
    void customerAndCounselorHaveDistinctEstablishedSubjects() throws Exception {
        assertEquals("demo-customer-001", subject("GET", "/api/v1/cases/C-001/messages", "customer-test-token", "127.0.0.1"));
        assertEquals("demo-customer-001", subject("PUT", "/api/v1/cases/C-001/selection", "customer-test-token", "127.0.0.1"));
        assertEquals("demo-counselor-001", subject("POST", "/api/v1/cases/C-001/offers", "counselor-test-token", "127.0.0.1"));
        // The service, rather than a token-selected impersonation, rejects counselor choice.
        assertEquals("demo-counselor-001", subject("PUT", "/api/v1/cases/C-001/selection", "counselor-test-token", "127.0.0.1"));
    }

    @Test
    void customerTokenCannotOpenCounselorReadsOrWrites() throws Exception {
        assertNull(subject("GET", "/api/v1/cases", "customer-test-token", "127.0.0.1"));
        assertNull(subject("GET", "/api/v1/cases/C-001", "customer-test-token", "127.0.0.1"));
        assertNull(subject("GET", "/api/v1/cases/C-001/quotes", "customer-test-token", "127.0.0.1"));
        assertNull(subject("POST", "/api/v1/cases/C-001/messages", "customer-test-token", "127.0.0.1"));
        assertNull(subject("POST", "/api/v1/cases/C-001/offers", "customer-test-token", "127.0.0.1"));
    }

    @Test
    void remoteOrForgedIdentityCannotEstablishADemoSubject() throws Exception {
        assertNull(subject("PUT", "/api/v1/cases/C-001/selection", "customer-test-token", "192.0.2.4"));
        assertNull(subject("PUT", "/api/v1/cases/C-001/selection", "wrong-token", "127.0.0.1"));
    }

    @Test
    void replacementReadsUseEachRoleButOnlyCounselorsCanRequestExecution() throws Exception {
        String result = "/api/v1/replacement-bookings/BRQ-" + "a".repeat(64);
        assertEquals("demo-customer-001", subject("GET", result, "customer-test-token", "127.0.0.1"));
        assertEquals("demo-counselor-001", subject("GET", result, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("GET", result, "customer-test-token", "192.0.2.4"));
        String command = "/api/v1/cases/C-001/replacement-bookings";
        assertEquals("demo-counselor-001", subject("POST", command, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("POST", command, "customer-test-token", "127.0.0.1"));
        assertNull(subject("PUT", result, "customer-test-token", "127.0.0.1"));
    }

    @Test
    void incidentPostAuthenticatesEachEstablishedRoleWithoutOpeningOtherBookingRoutes() throws Exception {
        String incident = "/api/v1/bookings/YB-001/incident-reports";
        assertEquals("demo-customer-001", subject("POST", incident, "customer-test-token", "127.0.0.1"));
        // Controller/service must see the counselor subject and reject its customer-only command.
        assertEquals("demo-counselor-001", subject("POST", incident, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("POST", incident, "customer-test-token", "192.0.2.4"));
        assertNull(subject("POST", incident, "wrong-token", "127.0.0.1"));
        for (String path : new String[]{"/api/v1/bookings/YB-001", incident + "/extra", incident + "/"}) {
            assertNull(subject("POST", path, "customer-test-token", "127.0.0.1"));
        }
        // API-001 booking reads are the only GET opened on booking routes (customer booking summary).
        for (String path : new String[]{incident + "/extra", incident + "/"}) {
            assertNull(subject("GET", path, "customer-test-token", "127.0.0.1"));
        }
        assertNull(subject("GET", incident, "customer-test-token", "127.0.0.1"));
        assertNull(subject("PUT", incident, "customer-test-token", "127.0.0.1"));
    }

    @Test
    void checkinIsCustomerPathWhileTaskAndClosureCommandsStayCounselorOnly() throws Exception {
        String checkin = "/api/v1/replacement-bookings/BRQ-" + "a".repeat(64) + "/checkin-confirmations";
        assertEquals("demo-customer-001", subject("POST", checkin, "customer-test-token", "127.0.0.1"));
        // The service rejects the counselor for this customer-only command.
        assertEquals("demo-counselor-001", subject("POST", checkin, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("POST", checkin, "customer-test-token", "192.0.2.4"));
        String task = "/api/v1/cases/C-001/tasks/TASK-3";
        String closure = "/api/v1/cases/C-001/closures";
        assertEquals("demo-counselor-001", subject("PATCH", task, "counselor-test-token", "127.0.0.1"));
        assertEquals("demo-counselor-001", subject("POST", closure, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("PATCH", task, "customer-test-token", "127.0.0.1"));
        assertNull(subject("POST", closure, "customer-test-token", "127.0.0.1"));
        assertNull(subject("PATCH", closure, "counselor-test-token", "127.0.0.1"));
        String evidence = "/api/v1/cases/C-001/evidence";
        assertEquals("demo-counselor-001", subject("POST", evidence, "counselor-test-token", "127.0.0.1"));
        assertNull(subject("POST", evidence, "customer-test-token", "127.0.0.1"));
    }

    @Test
    void customerReadsOwnBookingThroughTheSameRoleSplit() throws Exception {
        String booking = "/api/v1/bookings/YB-001";
        assertEquals("demo-customer-001", subject("GET", booking, "customer-test-token", "127.0.0.1"));
        assertNull(subject("GET", booking, "customer-test-token", "192.0.2.4"));
        assertNull(subject("GET", booking + "/extra", "customer-test-token", "127.0.0.1"));
    }

    private String subject(String method, String path, String token, String remote) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setLocalAddr("127.0.0.1");
        request.setRemoteAddr(remote);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Demo-Subject", "forged-subject");
        var name = new AtomicReference<String>();
        filter.doFilter(request, new MockHttpServletResponse(), (authenticated, response) -> {
            var principal = ((jakarta.servlet.http.HttpServletRequest) authenticated).getUserPrincipal();
            name.set(principal == null ? null : principal.getName());
        });
        return name.get();
    }
}
