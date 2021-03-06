package com.devebot.opflow;

import com.devebot.opflow.exception.OpflowBootstrapException;
import com.devebot.opflow.supports.OpflowConverter;
import com.devebot.opflow.supports.OpflowNetTool;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.util.Headers;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author acegik
 */
public class OpflowRestServer implements AutoCloseable {
    private final static Logger LOG = LoggerFactory.getLogger(OpflowRestServer.class);

    private final String instanceId;
    private final OpflowLogTracer logTracer;
    private final OpflowInfoCollector infoCollector;
    private final OpflowRpcChecker rpcChecker;
    private final Map<String, HttpHandler> defaultHandlers;
    private final String host;
    private final Integer port;
    private final Boolean enabled;
    private Undertow server;

    OpflowRestServer(OpflowInfoCollector _infoCollector,
            OpflowRpcChecker _rpcChecker,
            Map<String, Object> kwargs) throws OpflowBootstrapException {
        this(_infoCollector, _rpcChecker, kwargs, null);
    }
    
    OpflowRestServer(OpflowInfoCollector _infoCollector,
            OpflowRpcChecker _rpcChecker,
            Map<String, Object> kwargs,
            Map<String, HttpHandler> httpHandlers) throws OpflowBootstrapException {
        kwargs = OpflowUtil.ensureNotNull(kwargs);

        instanceId = OpflowUtil.getOptionField(kwargs, "instanceId", true);
        enabled = OpflowConverter.convert(OpflowUtil.getOptionField(kwargs, "enabled", null), Boolean.class);
        host = OpflowUtil.getOptionField(kwargs, "host", "0.0.0.0").toString();
        
        // detect the avaiable port
        Integer[] ports = OpflowConverter.convert(OpflowUtil.getOptionField(kwargs, "ports", new Integer[] {
            8787, 8989, 8990, 8991, 8992, 8993, 8994, 8995, 8996, 8997, 8998, 8999
        }), (new Integer[0]).getClass());
        port = OpflowNetTool.detectFreePort(ports);
        
        logTracer = OpflowLogTracer.ROOT.branch("restServerId", instanceId);
        
        infoCollector = _infoCollector;
        rpcChecker = _rpcChecker;
        
        defaultHandlers = new LinkedHashMap<>();
        defaultHandlers.put("/info", new InfoHandler());
        defaultHandlers.put("/ping", new PingHandler());
    }

    public Map<String, Object> info() {
        return OpflowUtil.buildOrderedMap()
                .put("commander", infoCollector.collect(OpflowInfoCollector.Scope.FULL))
                .toMap();
    }
    
    public OpflowRpcChecker.Info ping() {
        Map<String, Object> me = infoCollector.collect(OpflowInfoCollector.Scope.BASIC);
        try {
            return new OpflowRpcChecker.Info(me, this.rpcChecker.send(new OpflowRpcChecker.Ping()));
        } catch (Throwable exception) {
            return new OpflowRpcChecker.Info(me, exception);
        }
    }
    
    public Map<String, HttpHandler> getHttpHandlers() {
        return defaultHandlers;
    }
    
    public void serve() {
        serve(null, null);
    }

    public void serve(Map<String, HttpHandler> httpHandlers) {
        serve(httpHandlers, null);
    }
    
    public void serve(Map<String, HttpHandler> httpHandlers, Map<String, Object> kwargs) {
        if (enabled != null && Boolean.FALSE.equals(enabled)) {
            if (logTracer.ready(LOG, "info")) LOG.info(logTracer
                    .text("RestServer[${restServerId}].serve() is disable")
                    .stringify());
            return;
        }
        if (port == null) {
            if (logTracer.ready(LOG, "info")) LOG.info(logTracer
                    .text("RestServer[${restServerId}].serve() all of the ports are busy")
                    .stringify());
            return;
        }
        if (httpHandlers != null || kwargs != null) {
            if (logTracer.ready(LOG, "info")) LOG.info(logTracer
                    .text("RestServer[${restServerId}].serve() stop the current service")
                    .stringify());
            this.close();
        }
        if (server == null) {
            PathTemplateHandler ptHandler = Handlers.pathTemplate();
            for(Map.Entry<String, HttpHandler> entry:defaultHandlers.entrySet()) {
                ptHandler.add(entry.getKey(), entry.getValue());
            }

            if (httpHandlers != null) {
                for(Map.Entry<String, HttpHandler> entry:httpHandlers.entrySet()) {
                    ptHandler.add(entry.getKey(), entry.getValue());
                }
            }
            
            server = Undertow.builder()
                    .addHttpListener(port, host)
                    .setHandler(ptHandler)
                    .build();
            
            if (logTracer.ready(LOG, "info")) LOG.info(logTracer
                    .text("RestServer[${restServerId}].serve() a new HTTP server is created")
                    .stringify());
        }
        if (logTracer.ready(LOG, "info")) LOG.info(logTracer
                .put("port", port)
                .text("RestServer[${restServerId}].serve() Server listening on port ${port}")
                .stringify());
        server.start();
    }
    
    @Override
    public void close() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
    
    class InfoHandler implements HttpHandler {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                Map<String, Object> result = info();
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(OpflowJsontool.toString(result, getPrettyParam(exchange)));
            } catch (Exception exception) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.setStatusCode(500).getResponseSender().send(exception.toString());
            }
        }
    }
    
    class PingHandler implements HttpHandler {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            try {
                OpflowRpcChecker.Info result = ping();
                if (!"ok".equals(result.getStatus())) {
                    exchange.setStatusCode(503);
                }
                // render the result
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.getResponseSender().send(result.toString(getPrettyParam(exchange)));
            } catch (Exception exception) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.setStatusCode(500).getResponseSender().send(exception.toString());
            }
        }
    }
    
    private boolean getPrettyParam(HttpServerExchange exchange) {
        boolean pretty = false;
        Deque<String> prettyVals = exchange.getQueryParameters().get("pretty");
        if (prettyVals != null && !prettyVals.isEmpty()) {
            pretty = true;
        }
        return pretty;
    }
}
