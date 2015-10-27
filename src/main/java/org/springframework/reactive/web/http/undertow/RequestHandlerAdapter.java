package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import org.springframework.reactive.web.http.HttpHandler;
import org.springframework.reactive.web.http.ServerHttpRequest;
import org.springframework.reactive.web.http.ServerHttpResponse;
import org.springframework.util.Assert;


public class RequestHandlerAdapter implements io.undertow.server.HttpHandler {

    private final HttpHandler httpHandler;

    public RequestHandlerAdapter(HttpHandler httpHandler) {
        Assert.notNull(httpHandler, "'httpHandler' is required.");
        this.httpHandler = httpHandler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        RequestBodyPublisher requestBodyPublisher = new RequestBodyPublisher(exchange);
        ServerHttpRequest request = new UndertowServerHttpRequest(exchange, requestBodyPublisher);
        ResponseBodySubscriber responseBodySubscriber = new ResponseBodySubscriber(exchange);
        ServerHttpResponse response = new UndertowServerHttpResponse(exchange, responseBodySubscriber);
        httpHandler.handle(request, response);
    }
}
