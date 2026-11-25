package com.csro.backend.quotes;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.csro.backend.quotes.QuoteContracts.RankInput;
import com.csro.backend.quotes.QuoteContracts.Ranking;
import com.csro.backend.quotes.QuoteIntegrationException.Step;

/** API-025: ranking contains only the supplied immutable snapshot IDs and analysis text. */
@Component
public class QuoteRankingClient {
    private final QuoteHttpTransport transport;

    @Autowired
    public QuoteRankingClient(
            @Value("${quote.ranking.agent.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${quote.ranking.agent.service-token:}") String token) {
        this(QuoteHttpTransport.restClient(baseUrl), token);
    }

    QuoteRankingClient(RestClient client, String token) {
        this.transport = new QuoteHttpTransport(client, token, Step.API_025);
    }

    public Ranking rank(RankInput input) {
        Map<String, Object> wire;
        try { wire = QuoteContracts.rankingWire(input); }
        catch (RuntimeException invalid) { throw transport.invalidRequest(); }
        return transport.post("/internal/v1/alternative-rankings", wire,
                reply -> QuoteContracts.readRanking(reply.bytes(), input, reply.requestJson()));
    }
}
