/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.nio.protocol;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolException;
import org.apache.http.UnsupportedHttpVersionException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.NHttpConnection;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.nio.entity.ContentBufferEntity;
import org.apache.http.nio.entity.ContentOutputStream;
import org.apache.http.nio.params.HttpNIOParams;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.nio.util.ContentInputBuffer;
import org.apache.http.nio.util.ContentOutputBuffer;
import org.apache.http.nio.util.DirectByteBufferAllocator;
import org.apache.http.nio.util.SharedInputBuffer;
import org.apache.http.nio.util.SharedOutputBuffer;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpParamsLinker;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpExecutionContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EncodingUtils;
import org.apache.http.util.concurrent.Executor;

/**
 * HTTP service handler implementation that allocates content buffers of limited 
 * size upon initialization and is capable of controlling the frequency of I/O 
 * events in order to guarantee those content buffers do not ever get overflown. 
 * This helps ensure near constant memory footprint of HTTP connections and to 
 * avoid the 'out of memory' condition while streaming out response content.
 * 
 * <p>The service handler will delegate the task of processing requests and 
 * generating response content to an {@link Executor}, which is expected to
 * perform those tasks using dedicated worker threads in order to avoid 
 * blocking the I/O thread.</p>
 * 
 * @see HttpNIOParams#CONTENT_BUFFER_SIZE
 * 
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 *
 */
public class ThrottlingHttpServiceHandler extends NHttpServiceHandlerBase {

    protected final Executor executor;
    
    public ThrottlingHttpServiceHandler(
            final HttpProcessor httpProcessor, 
            final HttpResponseFactory responseFactory,
            final ConnectionReuseStrategy connStrategy,
            final ByteBufferAllocator allocator,
            final Executor executor,
            final HttpParams params) {
        super(httpProcessor, responseFactory, connStrategy, allocator, params);
        this.executor = executor;
    }

    public ThrottlingHttpServiceHandler(
            final HttpProcessor httpProcessor, 
            final HttpResponseFactory responseFactory,
            final ConnectionReuseStrategy connStrategy,
            final Executor executor,
            final HttpParams params) {
        this(httpProcessor, responseFactory, connStrategy, 
                new DirectByteBufferAllocator(), executor, params);
    }

    public void connected(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();

        int bufsize = this.params.getIntParameter(
                HttpNIOParams.CONTENT_BUFFER_SIZE, 20480);
        ServerConnState connState = new ServerConnState(bufsize, conn, allocator); 
        context.setAttribute(CONN_STATE, connState);

        if (this.eventListener != null) {
            this.eventListener.connectionOpen(conn);
        }
    }

    public void closed(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();
        
        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);

        connState.shutdown();
        
        if (this.eventListener != null) {
            this.eventListener.connectionClosed(conn);
        }
    }
    
    public void exception(final NHttpServerConnection conn, final HttpException httpex) {
        HttpContext context = conn.getContext();
        
        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);
        
        try {

            HttpResponse response = this.responseFactory.newHttpResponse(
                    HttpVersion.HTTP_1_0, 
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, 
                    context);
            HttpParamsLinker.link(response, this.params);
            handleException(httpex, response);
            response.setEntity(null);
            
            this.httpProcessor.process(response, context);
            
            synchronized (connState) {
                connState.setResponse(response);
                // Response is ready to be committed
                conn.requestOutput();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn);
            if (eventListener != null) {
                eventListener.fatalIOException(ex, conn);
            }
        } catch (HttpException ex) {
            shutdownConnection(conn);
            if (eventListener != null) {
                eventListener.fatalProtocolException(ex, conn);
            }
        }

    }

    public void requestReceived(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();
        
        final HttpRequest request = conn.getHttpRequest();
        final ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);

        synchronized (connState) {
            connState.setRequest(request);
            connState.setInputState(ServerConnState.REQUEST_RECEIVED);

            boolean contentExpected = false;
            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                if (entity != null) {
                    contentExpected = true;
                }
            }
            
            if (!contentExpected) {
                conn.suspendInput();
            }
            
            this.executor.execute(new Runnable() {
                
                public void run() {
                    try {

                        handleRequest(connState, conn);
                        
                    } catch (IOException ex) {
                        shutdownConnection(conn);
                        if (eventListener != null) {
                            eventListener.fatalIOException(ex, conn);
                        }
                    } catch (HttpException ex) {
                        shutdownConnection(conn);
                        if (eventListener != null) {
                            eventListener.fatalProtocolException(ex, conn);
                        }
                    }
                }
                
            });
        
            connState.notifyAll();
        }
        
    }

    public void inputReady(final NHttpServerConnection conn, final ContentDecoder decoder) {
        HttpContext context = conn.getContext();

        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);
        
        try {

            synchronized (connState) {
                ContentInputBuffer buffer = connState.getInbuffer();

                buffer.consumeContent(decoder);
                if (decoder.isCompleted()) {
                    connState.setInputState(ServerConnState.REQUEST_BODY_DONE);
                } else {
                    connState.setInputState(ServerConnState.REQUEST_BODY_STREAM);
                }
                
                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        }
        
    }
    
    public void responseReady(final NHttpServerConnection conn) {
        HttpContext context = conn.getContext();

        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);

        try {
        
            synchronized (connState) {
                HttpResponse response = connState.getResponse();
                if (connState.getOutputState() == ServerConnState.READY 
                        && response != null 
                        && !conn.isResponseSubmitted()) {

                    conn.submitResponse(response);
                    int statusCode = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();

                    if (statusCode >= 200 && entity == null) {
                        connState.resetOutput();
                        connState.resetInput();

                        if (!this.connStrategy.keepAlive(response, context)) {
                            conn.close();
                        } else {
                            // Ready for new request
                            conn.requestInput();
                        }
                    } else {
                        connState.setOutputState(ServerConnState.RESPONSE_SENT);
                    }
                }
                
                connState.notifyAll();
            }

        } catch (IOException ex) {
            shutdownConnection(conn);
            if (eventListener != null) {
                eventListener.fatalIOException(ex, conn);
            }
        } catch (HttpException ex) {
            shutdownConnection(conn);
            if (eventListener != null) {
                eventListener.fatalProtocolException(ex, conn);
            }
        }
    }

    public void outputReady(final NHttpServerConnection conn, final ContentEncoder encoder) {
        HttpContext context = conn.getContext();

        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);
        
        try {

            synchronized (connState) {
                HttpResponse response = connState.getResponse();
                ContentOutputBuffer buffer = connState.getOutbuffer();
                
                buffer.produceContent(encoder);
                if (encoder.isCompleted()) {
                    connState.resetOutput();
                    connState.resetInput();

                    if (!this.connStrategy.keepAlive(response, context)) {
                        conn.close();
                    } else {
                        // Ready for new request
                        conn.requestInput();
                    }
                } else {
                    connState.setOutputState(ServerConnState.RESPONSE_BODY_STREAM);
                }
                
                connState.notifyAll();
            }
            
        } catch (IOException ex) {
            shutdownConnection(conn);
            if (this.eventListener != null) {
                this.eventListener.fatalIOException(ex, conn);
            }
        }
    }
 
    private void shutdownConnection(final NHttpConnection conn) {
        HttpContext context = conn.getContext();

        ServerConnState connState = (ServerConnState) context.getAttribute(CONN_STATE);
        
        try {
            conn.shutdown();
        } catch (IOException ignore) {
        }
        if (connState != null) {
            connState.shutdown();
        }
    }
    
    private void waitForOutputState(
            final ServerConnState connState, 
            int expectedState) throws InterruptedIOException {
        synchronized (connState) {
            try {
                for (;;) {
                    int currentState = connState.getOutputState();
                    if (currentState == expectedState) {
                        break;
                    }
                    if (currentState == ServerConnState.SHUTDOWN) {
                        throw new InterruptedIOException("Service interrupted");
                    }
                    connState.wait();
                }
            } catch (InterruptedException ex) {
                connState.shutdown();
            }
        }
    }
    
    private void handleException(final HttpException ex, final HttpResponse response) {
        if (ex instanceof MethodNotSupportedException) {
            response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
        } else if (ex instanceof UnsupportedHttpVersionException) {
            response.setStatusCode(HttpStatus.SC_HTTP_VERSION_NOT_SUPPORTED);
        } else if (ex instanceof ProtocolException) {
            response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
        } else {
            response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
        byte[] msg = EncodingUtils.getAsciiBytes(ex.getMessage());
        ByteArrayEntity entity = new ByteArrayEntity(msg);
        entity.setContentType("text/plain; charset=US-ASCII");
        response.setEntity(entity);
    }
    
    private void handleRequest(
            final ServerConnState connState,
            final NHttpServerConnection conn) throws HttpException, IOException {

        waitForOutputState(connState, ServerConnState.READY);

        HttpContext context = conn.getContext();
        HttpRequest request = connState.getRequest();
        
        HttpParamsLinker.link(request, this.params);
        
        context.setAttribute(HttpExecutionContext.HTTP_CONNECTION, conn);
        context.setAttribute(HttpExecutionContext.HTTP_REQUEST, request);

        HttpVersion ver = request.getRequestLine().getHttpVersion();

        if (!ver.lessEquals(HttpVersion.HTTP_1_1)) {
            // Downgrade protocol version if greater than HTTP/1.1 
            ver = HttpVersion.HTTP_1_1;
        }

        HttpResponse response = null;

        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest entityReq = (HttpEntityEnclosingRequest) request;
            
            if (entityReq.expectContinue()) {
                response = this.responseFactory.newHttpResponse(
                        ver, 
                        HttpStatus.SC_CONTINUE, 
                        context);
                HttpParamsLinker.link(response, this.params);
                if (this.expectationVerifier != null) {
                    try {
                        this.expectationVerifier.verify(request, response, context);
                    } catch (HttpException ex) {
                        response = this.responseFactory.newHttpResponse(HttpVersion.HTTP_1_0, 
                                HttpStatus.SC_INTERNAL_SERVER_ERROR, context);
                        HttpParamsLinker.link(request, this.params);
                        handleException(ex, response);
                    }
                }
            
                if (response.getStatusLine().getStatusCode() < 200) {
                    
                    // Send 1xx response indicating the server expections
                    // have been met
                    synchronized (connState) {
                        connState.setResponse(response);
                        conn.requestOutput();
                        waitForOutputState(connState, ServerConnState.RESPONSE_SENT);
                        connState.resetOutput();
                    }
                    response = null;
                } else {
                    // Discard entity
                    conn.resetInput();
                    entityReq.setEntity(null);
                }

            }

            // Create a wrapper entity instead of the original one
            if (entityReq.getEntity() != null) {
                entityReq.setEntity(new ContentBufferEntity(
                        entityReq.getEntity(), 
                        connState.getInbuffer()));
            }
        }

        if (response == null) {
            response = this.responseFactory.newHttpResponse(
                    ver, 
                    HttpStatus.SC_OK, 
                    context);
            HttpParamsLinker.link(response, this.params);

            context.setAttribute(HttpExecutionContext.HTTP_RESPONSE, response);
            
            try {

                this.httpProcessor.process(request, context);

                HttpRequestHandler handler = null;
                if (this.handlerResolver != null) {
                    String requestURI = request.getRequestLine().getUri();
                    handler = this.handlerResolver.lookup(requestURI);
                }
                if (handler != null) {
                    handler.handle(request, response, context);
                } else {
                    response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
                }

            } catch (HttpException ex) {
                response = this.responseFactory.newHttpResponse(HttpVersion.HTTP_1_0, 
                        HttpStatus.SC_INTERNAL_SERVER_ERROR, context);
                HttpParamsLinker.link(response, this.params);
                handleException(ex, response);
            }
        }

        this.httpProcessor.process(response, context);

        if (!canResponseHaveBody(request, response)) {
            response.setEntity(null);
        }
        
        connState.setResponse(response);
        // Response is ready to be committed
        conn.requestOutput();

        if (response.getEntity() != null) {
            ContentOutputBuffer buffer = connState.getOutbuffer();
            OutputStream outstream = new ContentOutputStream(buffer);

            HttpEntity entity = response.getEntity();
            entity.writeTo(outstream);
            outstream.flush();
            outstream.close();
        }
    }
    
    static class ServerConnState {
        
        public static final int SHUTDOWN                   = -1;
        public static final int READY                      = 0;
        public static final int REQUEST_RECEIVED           = 1;
        public static final int REQUEST_BODY_STREAM        = 2;
        public static final int REQUEST_BODY_DONE          = 4;
        public static final int RESPONSE_SENT              = 8;
        public static final int RESPONSE_BODY_STREAM       = 16;
        public static final int RESPONSE_BODY_DONE         = 32;
        
        private final SharedInputBuffer inbuffer; 
        private final SharedOutputBuffer outbuffer;

        private volatile int inputState;
        private volatile int outputState;
        
        private volatile HttpRequest request;
        private volatile HttpResponse response;
        
        public ServerConnState(
                int bufsize, 
                final IOControl ioControl, 
                final ByteBufferAllocator allocator) {
            super();
            this.inbuffer = new SharedInputBuffer(bufsize, ioControl, allocator);
            this.outbuffer = new SharedOutputBuffer(bufsize, ioControl, allocator);
            this.inputState = READY;
            this.outputState = READY;
        }

        public ContentInputBuffer getInbuffer() {
            return this.inbuffer;
        }

        public ContentOutputBuffer getOutbuffer() {
            return this.outbuffer;
        }
        
        public int getInputState() {
            return this.inputState;
        }

        public void setInputState(int inputState) {
            this.inputState = inputState;
        }

        public int getOutputState() {
            return this.outputState;
        }

        public void setOutputState(int outputState) {
            this.outputState = outputState;
        }

        public HttpRequest getRequest() {
            return this.request;
        }

        public void setRequest(final HttpRequest request) {
            this.request = request;
        }

        public HttpResponse getResponse() {
            return this.response;
        }

        public void setResponse(final HttpResponse response) {
            this.response = response;
        }

        public void shutdown() {
            this.inbuffer.shutdown();
            this.outbuffer.shutdown();
            this.inputState = SHUTDOWN;
            this.outputState = SHUTDOWN;
        }

        public void resetInput() {
            this.inbuffer.reset();
            this.request = null;
            this.inputState = READY;
        }
        
        public void resetOutput() {
            this.outbuffer.reset();
            this.response = null;
            this.outputState = READY;
        }
        
    }    
    
}
