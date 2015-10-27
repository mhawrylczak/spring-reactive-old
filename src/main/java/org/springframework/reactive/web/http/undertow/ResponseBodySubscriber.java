package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.util.Assert;

import java.io.IOException;
import java.nio.ByteBuffer;


public class ResponseBodySubscriber implements Subscriber<ByteBuffer> {

    private static final Log logger = LogFactory.getLog(ResponseBodySubscriber.class);

    private final HttpServerExchange exchange;

    private Subscription subscription;

    public ResponseBodySubscriber(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        this.subscription.request(1);
    }

    @Override
    public void onNext(ByteBuffer bytes) {
        //TODO handle many buffers
        exchange.getResponseSender().send(bytes);
    }

    @Override
    public void onError(Throwable t) {
        logger.error("ResponseBodySubscriber error", t);
    }

    @Override
    public void onComplete() {
        logger.debug("onComplete");
    }
}
