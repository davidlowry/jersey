/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.test.inmemory.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.internal.PropertiesDelegate;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.glassfish.jersey.message.internal.OutboundMessageContext;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * In-memory client connector.
 *
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class InMemoryConnector implements Connector {

    private static final Logger LOGGER = Logger.getLogger(InMemoryConnector.class.getName());
    private final ApplicationHandler appHandler;
    private final URI baseUri;

    /**
     * Constructor.
     *
     * @param baseUri application base URI.
     * @param application RequestInvoker instance which represents application.
     */
    public InMemoryConnector(final URI baseUri, final ApplicationHandler application) {
        this.baseUri = baseUri;
        this.appHandler = application;
    }


    /**
     * In memory container response writer.
     */
    public static class InMemoryResponseWriter implements ContainerResponseWriter {
        private MultivaluedMap<String, String> headers;
        private ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private boolean committed;
        private Response.StatusType statusInfo;


        @Override
        public OutputStream writeResponseStatusAndHeaders(long contentLength, ContainerResponse responseContext) throws ContainerException {
            List<Object> length = Lists.newArrayList();
            length.add(String.valueOf(contentLength));

            responseContext.getHeaders().put(HttpHeaders.CONTENT_LENGTH, length);
            headers = responseContext.getStringHeaders();
            statusInfo = responseContext.getStatusInfo();
            return baos;
        }

        @Override
        public boolean suspend(long timeOut, TimeUnit timeUnit, TimeoutHandler timeoutHandler) {
            return false;
        }

        @Override
        public void setSuspendTimeout(long timeOut, TimeUnit timeUnit) throws IllegalStateException {
            throw new UnsupportedOperationException("not supported");
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void failure(Throwable error) {
            throw new RuntimeException("InMemoryResponseWriter.failure called.", error);
        }

        @Override
        public boolean enableResponseBuffering() {
            return true;
        }

        /**
         * Get the written entity.
         * @return Byte array which contains the entity written by the server.
         */
        public byte[] getEntity() {
            if (!committed) {
                throw new IllegalStateException("Response is not committed yet.");
            }
            return baos.toByteArray();
        }

        /**
         * Return response headers.
         * @return headers.
         */
        public MultivaluedMap<String, String> getHeaders() {
            return headers;
        }

        /**
         * Returns response status info.
         * @return status info.
         */
        public Response.StatusType getStatusInfo() {
            return statusInfo;
        }
    }


    /**
     * {@inheritDoc}
     * <p/>
     * Transforms client-side request to server-side and invokes it on provided application ({@link ApplicationHandler}
     * instance).
     *
     * @param clientRequest client side request to be invoked.
     */
    @Override
    public ClientResponse apply(final ClientRequest clientRequest) {
        PropertiesDelegate propertiesDelegate = new MapPropertiesDelegate();

        final ContainerRequest containerRequest = new ContainerRequest(baseUri,
                clientRequest.getUri(), clientRequest.getMethod(),
                null, propertiesDelegate);

        final ByteArrayOutputStream clientOutput = new ByteArrayOutputStream();
        if (clientRequest.getEntity() != null) {
            clientRequest.setStreamProvider(new OutboundMessageContext.StreamProvider() {
                @Override
                public OutputStream getOutputStream(int contentLength) throws IOException {
                    containerRequest.getHeaders().putAll(clientRequest.getStringHeaders());
                    List<String> length = Lists.newArrayList();
                    length.add(String.valueOf(contentLength));
                    containerRequest.getHeaders().put(HttpHeaders.CONTENT_LENGTH, length);
                    return clientOutput;
                }
            });
            clientRequest.enableBuffering();

            try {
                clientRequest.writeEntity();
            } catch (IOException e) {
                final String msg = "Error while writing entity to the output stream.";
                LOGGER.log(Level.SEVERE, msg, e);
                throw new ProcessingException(msg, e);
            }
        }
        containerRequest.setEntityStream(new ByteArrayInputStream(clientOutput.toByteArray()));


        boolean followRedirects = PropertiesHelper.getValue(clientRequest.getConfiguration().getProperties(),
                ClientProperties.FOLLOW_REDIRECTS, true);

        final InMemoryResponseWriter inMemoryResponseWriter = new InMemoryResponseWriter();
        containerRequest.setWriter(inMemoryResponseWriter);
        containerRequest.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return null;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public String getAuthenticationScheme() {
                return null;
            }
        });
        appHandler.handle(containerRequest);

        return tryFollowRedirects(followRedirects,
                createClientResponse(
                        clientRequest,
                        inMemoryResponseWriter),
                new ClientRequest(clientRequest));

    }

    @Override
    public Future<?> apply(final ClientRequest request, final AsyncConnectorCallback callback) {
        return MoreExecutors.sameThreadExecutor().submit(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.response(apply(request));
                } catch (ProcessingException ex) {
                    throw ex;
                } catch (Throwable t) {
                    callback.failure(t);
                }
            }
        });
    }

    @Override
    public void close() {
        // do nothing
    }


    private ClientResponse createClientResponse(final ClientRequest clientRequest,
                                                final InMemoryResponseWriter responseWriter) {
        final ClientResponse clientResponse = new ClientResponse(responseWriter.getStatusInfo(), clientRequest);
        clientResponse.getHeaders().putAll(responseWriter.getHeaders());
        clientResponse.setEntityStream(new ByteArrayInputStream(responseWriter.getEntity()));
        clientResponse.setStatus(responseWriter.getStatusInfo().getStatusCode());
        return clientResponse;
    }

    private ClientResponse tryFollowRedirects(boolean followRedirects, ClientResponse response, ClientRequest request) {
        final int statusCode = response.getStatus();
        if (!followRedirects || statusCode < 302 || statusCode > 307) {
            return response;
        }

        switch (statusCode) {
            case 303:
                request.setMethod("GET");
                // intentionally no break
            case 302:
            case 307:
                request.setUri(response.getLocation());

                return apply(request);
            default:
                return response;
        }
    }

    @Override
    public String getName() {
        return "Jersey InMemory Container Client";
    }
}
