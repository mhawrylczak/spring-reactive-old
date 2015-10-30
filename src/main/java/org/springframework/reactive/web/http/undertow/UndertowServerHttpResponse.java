package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.reactive.web.http.ServerHttpResponse;
import reactor.Publishers;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;


public class UndertowServerHttpResponse implements ServerHttpResponse {
    private final HttpServerExchange exchange;
    private final HttpHeaders headers;

    private final ResponseBodySubscriber responseBodySubscriber;

    private boolean headersWritten = false;

    public UndertowServerHttpResponse(HttpServerExchange exchange, ResponseBodySubscriber responseBodySubscriber) {
        this.exchange = exchange;
        this.responseBodySubscriber = responseBodySubscriber;
        this.headers = new HttpHeaders();
    }

    @Override
    public void setStatusCode(HttpStatus status) {
        exchange.setResponseCode(status.value());
    }

    @Override
    public Publisher<Void> writeWith(Publisher<ByteBuffer> contentPublisher) {
        applyHeaders();
        return (s -> contentPublisher.subscribe(responseBodySubscriber));
    }

    @Override
    public HttpHeaders getHeaders() {
        return (this.headersWritten ? HttpHeaders.readOnlyHttpHeaders(this.headers) : this.headers);
    }

    @Override
    public Publisher<Void> writeHeaders() {
        applyHeaders();
        return Publishers.empty();
    }

    private void applyHeaders() {
        if (!this.headersWritten) {
            for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
                String headerName = entry.getKey();
                exchange.getResponseHeaders().addAll(HttpString.tryFromString(headerName), entry.getValue());
            }
            this.headersWritten = true;
        }
    }
}
