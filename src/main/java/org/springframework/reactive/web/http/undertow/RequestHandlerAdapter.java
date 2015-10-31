/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

/**
 * @author Marek Hawrylczak
 */
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
                s.request(Long.MAX_VALUE);
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
