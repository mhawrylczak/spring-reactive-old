package org.springframework.reactive.web.http.undertow;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.reactive.web.http.HttpServer;
import org.springframework.reactive.web.http.HttpServerSupport;
import org.springframework.util.Assert;


public class UndertowHttpServer extends HttpServerSupport
        implements InitializingBean, HttpServer {

    private Undertow undertowServer;

    private boolean running;

    @Override
    public void afterPropertiesSet() throws Exception {
        Assert.notNull(getHttpHandler());

        HttpHandler handler = new RequestHandlerAdapter(getHttpHandler());

        undertowServer = Undertow.builder()
                .addHttpListener(getPort() != -1 ? getPort() : 8080, "localhost" )
                .setHandler(handler)
                .build();
    }

    @Override
    public void start() {
        if (!running) {
            undertowServer.start();
            running = true;
        }

    }

    @Override
    public void stop() {
        if (running) {
            undertowServer.stop();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
