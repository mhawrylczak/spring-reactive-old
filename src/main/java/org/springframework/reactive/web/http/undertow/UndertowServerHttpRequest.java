package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.reactive.web.http.ServerHttpRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;


public class UndertowServerHttpRequest implements ServerHttpRequest {

    private final HttpServerExchange exchange;

    private final Publisher<ByteBuffer> requestBodyPublisher;

    private HttpHeaders headers;

    public UndertowServerHttpRequest(HttpServerExchange exchange, Publisher<ByteBuffer> requestBodyPublisher) {
        this.exchange = exchange;
        this.requestBodyPublisher = requestBodyPublisher;
    }

    @Override
    public Publisher<ByteBuffer> getBody() {
        return this.requestBodyPublisher;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.valueOf(exchange.getRequestMethod().toString());
    }

    @Override
    public URI getURI() {
        try {
            return new URI(exchange.getRequestURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Could not get URI: " + ex.getMessage(), ex);
        }
    }

    @Override
    public HttpHeaders getHeaders() {
        if (this.headers == null) {
            this.headers = new HttpHeaders();
            for(HeaderValues headerValues : exchange.getRequestHeaders()){
                for(String value : headerValues ){
                    this.headers.add(headerValues.getHeaderName().toString(), value);
                }
            }
        }
        return this.headers;
    }
}
