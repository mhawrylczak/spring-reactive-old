package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.reactive.web.http.HttpHandler;
import org.springframework.reactive.web.http.ServerHttpRequest;
import org.springframework.reactive.web.http.ServerHttpResponse;
import org.springframework.util.Assert;


public class RequestHandlerAdapter implements io.undertow.server.HttpHandler {

    private final Logger LOG = LoggerFactory.getLogger(RequestHandlerAdapter.class);

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
        exchange.dispatch();
        httpHandler.handle(request, response).subscribe(new Subscriber<Void>() {
            @Override
            public void onSubscribe(Subscription s) {
                LOG.debug("onSubscribe");
            }

            @Override
            public void onNext(Void aVoid) {
                LOG.debug("onNext");
            }

            @Override
            public void onError(Throwable t) {
                exchange.endExchange();
                LOG.error("onError", t);
            }

            @Override
            public void onComplete() {
                exchange.endExchange();
                LOG.debug("onComplete");
            }
        });
    }
}
