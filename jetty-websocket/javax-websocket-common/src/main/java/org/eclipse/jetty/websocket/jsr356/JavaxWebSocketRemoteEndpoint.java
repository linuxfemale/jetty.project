//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.SendHandler;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.jsr356.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.jsr356.messages.MessageWriter;

public class JavaxWebSocketRemoteEndpoint implements javax.websocket.RemoteEndpoint, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(JavaxWebSocketRemoteEndpoint.class);

    protected final JavaxWebSocketSession session;
    private final FrameHandler.Channel channel;
    protected BatchMode batchMode = BatchMode.OFF; // TODO: should this be defaulted to AUTO instead?
    protected byte messageType = -1;

    protected JavaxWebSocketRemoteEndpoint(JavaxWebSocketSession session, FrameHandler.Channel channel)
    {
        this.session = session;
        this.channel = channel;
    }

    @Override
    public void flushBatch() throws IOException
    {
        try (SharedBlockingCallback.Blocker blocker = session.getBlocking().acquire())
        {
            channel.flushBatch(blocker);
        }
    }

    @Override
    public boolean getBatchingAllowed()
    {
        return getBatchMode() == BatchMode.ON;
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException
    {
        if (getBatchMode() == BatchMode.ON && !allowed)
        {
            flushBatch();
        }
        setBatchMode(allowed ? BatchMode.ON : BatchMode.OFF);
    }

    public long getIdleTimeout()
    {
        return channel.getIdleTimeout(TimeUnit.MILLISECONDS);
    }

    public void setIdleTimeout(long ms)
    {
        channel.setIdleTimeout(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        if (frame instanceof DataFrame)
        {
            try
            {
                byte opcode = frame.getOpCode();

                if (messageType == -1) // new message
                {
                    if ((opcode == OpCode.BINARY) || (opcode == OpCode.TEXT))
                    {
                        messageType = opcode;
                    }
                    else
                    {
                        throw new WebSocketException("Encountered invalid Data Frame opcode " + opcode);
                    }
                }
                else if ((messageType == OpCode.BINARY) && (opcode == OpCode.TEXT))
                {
                    throw new WebSocketException("Cannot start TEXT message when BINARY message is not complete yet");
                }
                else if ((messageType == OpCode.TEXT) && (opcode == OpCode.BINARY))
                {
                    throw new WebSocketException("Cannot start BINARY message when TEXT message is not complete yet");
                }

            }
            catch (Throwable t)
            {
                callback.failed(t);
                return;
            }
        }

        try
        {
            channel.sendFrame(frame, callback, batchMode);
        }
        finally
        {
            if (frame.isFin())
            {
                messageType = -1;
            }
        }
    }

    public void sendObject(Object data, Callback callback) throws IOException, EncodeException
    {
        try
        {
            assertMessageNotNull(data);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendObject({}, {})", data, callback);
            }

            Encoder encoder = session.getEncoders().getInstanceFor(data.getClass());
            if (encoder == null)
            {
                throw new IllegalArgumentException("No encoder for type: " + data.getClass());
            }

            if (encoder instanceof Encoder.Text)
            {
                Encoder.Text text = (Encoder.Text) encoder;
                String msg = text.encode(data);
                sendFrame(new TextFrame().setPayload(msg), callback, batchMode);
                return;
            }

            if (encoder instanceof Encoder.TextStream)
            {
                Encoder.TextStream etxt = (Encoder.TextStream) encoder;
                try (MessageWriter writer = new MessageWriter(this, session.getPolicy().getMaxTextMessageBufferSize(), session.getContainerBufferPool()))
                {
                    writer.setCallback(callback);
                    etxt.encode(data, writer);
                }
                return;
            }

            if (encoder instanceof Encoder.Binary)
            {
                Encoder.Binary ebin = (Encoder.Binary) encoder;
                ByteBuffer buf = ebin.encode(data);
                sendFrame(new BinaryFrame().setPayload(buf), callback, batchMode);
                return;
            }

            if (encoder instanceof Encoder.BinaryStream)
            {
                Encoder.BinaryStream ebin = (Encoder.BinaryStream) encoder;
                try (MessageOutputStream out = new MessageOutputStream(this, session.getPolicy().getMaxBinaryMessageBufferSize(), session.getContainerBufferPool()))
                {
                    out.setCallback(callback);
                    ebin.encode(data, out);
                }
                return;
            }

            throw new IllegalArgumentException("Unknown encoder type: " + encoder);
        }
        catch (RuntimeException | IOException | EncodeException e)
        {
            callback.failed(e);
            throw e;
        }
        catch (Throwable t)
        {
            callback.failed(t);
            LOG.warn("Unable to send Object " + data, t);
        }
    }

    @Override
    public void sendPing(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPing({})", BufferUtil.toDetailString(data));
        }
        // TODO: is this supposed to be a blocking call?
        // TODO: what to do on excessively large payloads (error and close connection per RFC6455, or truncate?)
        sendFrame(new PingFrame().setPayload(data), Callback.NOOP, batchMode);
    }

    @Override
    public void sendPong(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPong({})", BufferUtil.toDetailString(data));
        }
        // TODO: is this supposed to be a blocking call?
        // TODO: what to do on excessively large payloads (error and close connection per RFC6455, or truncate?)
        sendFrame(new PongFrame().setPayload(data), Callback.NOOP, batchMode);
    }

    protected void assertMessageNotNull(Object data)
    {
        if (data == null)
        {
            throw new IllegalArgumentException("message cannot be null");
        }
    }

    protected void assertSendHandlerNotNull(SendHandler handler)
    {
        if (handler == null)
        {
            throw new IllegalArgumentException("SendHandler cannot be null");
        }
    }

    protected BatchMode getBatchMode()
    {
        return this.batchMode;
    }

    protected void setBatchMode(BatchMode batchMode)
    {
        this.batchMode = batchMode;
    }
}
