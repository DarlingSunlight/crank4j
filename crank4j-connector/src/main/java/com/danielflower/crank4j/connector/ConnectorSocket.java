package com.danielflower.crank4j.connector;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

@WebSocket
public class ConnectorSocket {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSocket.class);

    private final HttpClient httpClient;
    private final URI targetURI;


    private Session session;
    private Request requestToTarget;

    public ConnectorSocket(HttpClient httpClient, URI targetURI) {

        this.httpClient = httpClient;
        this.targetURI = targetURI;
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        System.out.printf("Connection closed: %d - %s%n", statusCode, reason);
        this.session = null;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        log.info("Connected on " + session);
        this.session = session;

    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        log.debug("Got message " + msg);
        if (requestToTarget == null) {
            String[] bits = msg.split(" ");

            URI dest = targetURI.resolve(bits[1]);
            String method = bits[0];
            log.info("Going to " + method + " " + dest);
            requestToTarget = httpClient.newRequest(dest)
                .method(method);
        } else {
            int pos = msg.indexOf(':');
            if (pos > 0) {
                String header = msg.substring(0, pos);
                String value = msg.substring(pos + 1).trim();
                log.info("Target request header " + header + "=" + value);
                requestToTarget.header(header, value);
            } else {
                log.info("Request headers received");
                requestToTarget.onResponseBegin(response -> {
                    try {
                        log.info("Sending status to router " + response.getStatus());
                        session.getRemote().sendString("HTTP/1.1 " + response.getStatus() + " " + response.getReason() + "\r\n");
                    } catch (IOException e) {
                        log.warn("Uh oh", e);
                        // TODO: close stuff?
                    }
                });
                requestToTarget.onResponseHeaders(response -> {
                    try {
                        HttpFields headers = response.getHeaders();
                        for (HttpField header : headers) {
                            String name = header.getName();
                            String value = header.getValue(); // header.getValues() breaks dates
                            log.info("Sending response header to router " + name + "=" + value);
                            session.getRemote().sendString(name + ": " + value + "\r\n");
                        }
                        session.getRemote().sendString("\r\n");
                    } catch (IOException e) {
                        log.warn("Oh on", e);
                    }
                });
                requestToTarget.onResponseContentAsync((response, byteBuffer, callback) -> {
                    ByteBuffer responseBytes = ByteBuffer.allocate(byteBuffer.capacity());
                    responseBytes.put(byteBuffer);
                    responseBytes.position(0);
                    log.info("Sending " + byteBuffer.capacity() + " bytes to router");
                    session.getRemote().sendBytes(responseBytes, new WriteCallback() {
                        @Override
                        public void writeFailed(Throwable throwable) {
                            log.info("Failed", throwable);
                            callback.failed(throwable);
                        }

                        @Override
                        public void writeSuccess() {
                            log.info("Connector->target write success");
                            callback.succeeded();
                        }
                    });
                });
                requestToTarget.send(result -> {
                    if (result.isSucceeded()) {
                        log.info("Closing websocket because response fully processed");
                        session.close(new CloseStatus(1000, "Proxy complete"));
                    } else {
                        log.warn("Failed for " + result.getResponse(), result.getFailure());
                        session.close(new CloseStatus(1011, result.getFailure().getMessage()));
                    }
                });
            }
        }
    }

    @OnWebSocketFrame
    public void onWebsocketFrame(Frame frame) {
        if (frame instanceof BinaryFrame) {
            log.info("Got frame " + frame);
        }
    }
}