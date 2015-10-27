package org.springframework.reactive.web.http.undertow;

import io.undertow.server.HttpServerExchange;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.util.Assert;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSinkChannel;

import java.io.IOException;
import java.nio.ByteBuffer;


public class ResponseBodySubscriber implements Subscriber<ByteBuffer>, ChannelListener<StreamSinkChannel> {

    private static final Log logger = LogFactory.getLog(ResponseBodySubscriber.class);

    private final HttpServerExchange exchange;

    private Subscription subscription;

    private ByteBuffer buffer;

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
        this.buffer = bytes;
        final StreamSinkChannel responseChannel = exchange.getResponseChannel();
        try {
            int c;
            do {
                c = responseChannel.write(buffer);
            } while (buffer.hasRemaining() && c > 0);
            if (buffer.hasRemaining()) {
                responseChannel.getWriteSetter().set(this);
                responseChannel.resumeWrites();
            } else {
                this.subscription.request(1);
            }

        } catch (IOException ex) {
            //TODO
            ex.printStackTrace();
        }

//        exchange.getResponseSender().send(bytes);
//        this.subscription.request(1);
    }

    @Override
    public void handleEvent(StreamSinkChannel channel) {
        try {
            int c;
            do {
                c = channel.write(buffer);
            } while (buffer.hasRemaining() && c > 0);
            if (buffer.hasRemaining()) {
                channel.resumeWrites();
                return;
            } else {
                this.subscription.request(1);
            }
        } catch (IOException ex) {
            //TODO
            ex.printStackTrace();
        }
    }

    @Override
    public void onError(Throwable t) {
        logger.error("ResponseBodySubscriber error", t);
    }

    @Override
    public void onComplete() {
        if (exchange.getResponseChannel() != null) {
            writeDone(exchange.getResponseChannel());
        }
        logger.debug("onComplete");
    }

    protected void writeDone(final StreamSinkChannel channel) {
        try {
            channel.shutdownWrites();

            if (!channel.flush()) {
                channel.getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                    @Override
                    public void handleEvent(StreamSinkChannel o) {
                        IoUtils.safeClose(channel);
                    }
                }, ChannelListeners.closingChannelExceptionHandler()));
                channel.resumeWrites();

            }
        } catch (IOException ex) {
            //TODO
            ex.printStackTrace();
        }
    }
}
