package com.csro.backend.quotes;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.csro.backend.quotes.QuoteContracts.SearchInput;
import com.csro.backend.quotes.QuoteContracts.SearchResult;
import com.csro.backend.quotes.QuoteIntegrationException.Step;

/** API-028: supplier search only. This client never confirms inventory or writes business state. */
@Component
public class QuoteSupplierClient {
    private final QuoteHttpTransport transport;

    @Autowired
    public QuoteSupplierClient(
            @Value("${booking.reconfirmation.mock.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${booking.reconfirmation.mock.service-token:}") String token) {
        this(QuoteHttpTransport.restClient(baseUrl), token);
    }

    QuoteSupplierClient(RestClient client, String token) {
        this.transport = new QuoteHttpTransport(client, token, Step.API_028);
    }

    public SearchResult search(SearchInput input) {
        Map<String, Object> wire;
        try { wire = QuoteContracts.searchWire(input); }
        catch (RuntimeException invalid) { throw transport.invalidRequest(); }
        return transport.post("/mock-supplier/v1/availability-searches", wire,
                reply -> QuoteContracts.readSearch(reply.bytes(), input, reply.requestJson()));
    }
}
