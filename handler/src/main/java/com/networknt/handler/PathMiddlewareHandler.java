package com.networknt.handler;

import com.networknt.config.Config;
import com.networknt.handler.config.HandlerConfig;
import com.networknt.handler.config.HandlerPath;
import com.networknt.handler.config.NamedRequestChain;
import com.networknt.service.ServiceUtil;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Nicholas Azar
 */
public class PathMiddlewareHandler implements NonFunctionalMiddlewareHandler {

    private static final String CONFIG_NAME = "handler";
    public static HandlerConfig config = (HandlerConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, HandlerConfig.class);
    private volatile HttpHandler next;
    private String handlerName;

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // Doesn't get called.
    }

    List<HandlerPath> getHandlerPaths() {
        return config.getPathHandlers().stream()
                .filter(pathHandler -> pathHandler.getHandlerName().equals(handlerName))
                .filter(pathHandler -> pathHandler.getPaths() != null && pathHandler.getPaths().size() > 0)
                .flatMap(pathHandler -> pathHandler.getPaths().stream())
                .collect(Collectors.toList());
    }

    private HttpHandler getHandler(Object endPoint, List<Object> middlewareList) {
        HttpHandler httpHandler = null;
        try {
            Object object = ServiceUtil.construct(endPoint);
            if (object instanceof HttpHandler) {
                httpHandler = (HttpHandler) object;
                List<Object> updatedList = new ArrayList<>(middlewareList);
                Collections.reverse(updatedList);
                for (Object middleware : updatedList) {
                    Object constructedMiddleware = ServiceUtil.construct(middleware);
                    if (constructedMiddleware instanceof MiddlewareHandler) {
                        MiddlewareHandler middlewareHandler = (MiddlewareHandler) constructedMiddleware;
                        if (middlewareHandler.isEnabled()) {
                            httpHandler = middlewareHandler.setNext(httpHandler);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed when retrieving Handler.", e);
        }
        return httpHandler;
    }

    public PathMiddlewareHandler() {}

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        RoutingHandler routingHandler = Handlers.routing().setFallbackHandler(next);
        for (HandlerPath handlerPath : getHandlerPaths()) {
            try {
                if (handlerPath.getNamedRequestChain() == null || handlerPath.getNamedRequestChain().length() == 0) {
                    HttpHandler httpHandler = getHandler(handlerPath.getEndPoint(), handlerPath.getMiddleware());
                    if (httpHandler != null) {
                        routingHandler.add(handlerPath.getHttpVerb(), handlerPath.getPath(), httpHandler);
                    }
                } else {
                    // Handle named request chains.
                    List<NamedRequestChain> requestChains = config.getNamedRequestChain().stream()
                            .filter(namedRequestChain -> namedRequestChain.getName().equals(handlerPath.getNamedRequestChain())).collect(Collectors.toList());
                    if (requestChains != null && requestChains.size() > 0) {
                        HttpHandler httpHandler = getHandler(requestChains.get(0).getEndPoint(), requestChains.get(0).getMiddleware());
                        if (httpHandler != null) {
                            routingHandler.add(handlerPath.getHttpVerb(), handlerPath.getPath(), httpHandler);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to add PathMiddlewareHandler.", e);
            }
        }
        this.next = routingHandler;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(HandlerConfig.class.getName(), Config.getInstance().getJsonMapConfigNoCache(CONFIG_NAME), null);
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    // Exposed for testing.
    protected void setConfig(String configName) {
        config = (HandlerConfig) Config.getInstance().getJsonObjectConfig(configName, HandlerConfig.class);
    }
}
