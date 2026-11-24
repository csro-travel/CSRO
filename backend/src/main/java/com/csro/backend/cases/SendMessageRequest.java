package com.csro.backend.cases;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = SendMessageRequest.Deserializer.class)
record SendMessageRequest(String body) {
    static final class Deserializer extends ValueDeserializer<SendMessageRequest> {
        @Override
        public SendMessageRequest deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.START_OBJECT) || !"body".equals(parser.nextName())
                    || parser.nextToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(this, "Expected an object with a string body field");
            }
            String body = parser.getString();
            if (parser.nextToken() != JsonToken.END_OBJECT) {
                return context.reportInputMismatch(this, "Expected exactly one body field");
            }
            return new SendMessageRequest(body);
        }
    }
}
