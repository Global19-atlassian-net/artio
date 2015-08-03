/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.framer;

import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.decoder.LogonDecoder;
import uk.co.real_logic.fix_gateway.replication.GatewayPublication;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;
import uk.co.real_logic.fix_gateway.util.AsciiFlyweight;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static java.nio.channels.SelectionKey.OP_READ;
import static uk.co.real_logic.fix_gateway.dictionary.StandardFixConstants.START_OF_HEADER;
import static uk.co.real_logic.fix_gateway.engine.framer.SessionIds.DUPLICATE_SESSION;
import static uk.co.real_logic.fix_gateway.library.session.Session.UNKNOWN;
import static uk.co.real_logic.fix_gateway.messages.MessageStatus.*;
import static uk.co.real_logic.fix_gateway.util.AsciiFlyweight.UNKNOWN_INDEX;

/**
 * Handles incoming data from sockets
 */
public class ReceiverEndPoint
{
    private static final byte BODY_LENGTH_FIELD = 9;

    private static final int COMMON_PREFIX_LENGTH = "8=FIX.4.2 ".length();
    private static final int START_OF_BODY_LENGTH = COMMON_PREFIX_LENGTH + 2;

    private static final byte CHECKSUM0 = 1;
    private static final byte CHECKSUM1 = (byte) '1';
    private static final byte CHECKSUM2 = (byte) '0';
    private static final byte CHECKSUM3 = (byte) '=';

    private static final int MIN_CHECKSUM_SIZE = " 10=".length() + 1;
    public static final int SOCKET_DISCONNECTED = -1;
    public static final int UNKNOWN_MESSAGE_TYPE = -1;

    private final LogonDecoder logon = new LogonDecoder();

    private final SocketChannel channel;
    private final GatewayPublication publication;
    private final long connectionId;
    private final SessionIdStrategy sessionIdStrategy;
    private final SessionIds sessionIds;
    private final AtomicCounter messagesRead;
    private final Framer framer;
    private final AtomicBuffer buffer;
    private final AsciiFlyweight string;
    private final ByteBuffer byteBuffer;

    private long sessionId;
    private int usedBufferData = 0;
    private boolean hasDisconnected = false;
    private SelectionKey selectionKey;

    public ReceiverEndPoint(
        final SocketChannel channel,
        final int bufferSize,
        final GatewayPublication publication,
        final long connectionId,
        final long sessionId,
        final SessionIdStrategy sessionIdStrategy,
        final SessionIds sessionIds,
        final AtomicCounter messagesRead,
        final Framer framer)
    {
        this.channel = channel;
        this.publication = publication;
        this.connectionId = connectionId;
        this.sessionId = sessionId;
        this.sessionIdStrategy = sessionIdStrategy;
        this.sessionIds = sessionIds;
        this.messagesRead = messagesRead;
        this.framer = framer;

        buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(bufferSize));
        string = new AsciiFlyweight(buffer);
        byteBuffer = buffer.byteBuffer();
    }

    public SocketChannel channel()
    {
        return channel;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public int pollForData()
    {
        try
        {
            final int bytesReceived = readData();
            frameMessages();
            return bytesReceived;
        }
        catch (final ClosedChannelException ex)
        {
            onDisconnectDetected();
            return 1;
        }
        catch (final IOException ex)
        {
            // TODO: log
            ex.printStackTrace();
            onDisconnectDetected();
            return 1;
        }
    }

    private int readData() throws IOException
    {
        final int dataRead = channel.read(byteBuffer);
        if (dataRead != SOCKET_DISCONNECTED)
        {
            if (dataRead > 0)
            {
                DebugLogger.log("Read     %s\n", buffer, 0, dataRead);
            }
            usedBufferData += dataRead;
        }
        else
        {
            onDisconnectDetected();
        }
        return dataRead;
    }

    private void frameMessages()
    {
        int offset = 0;
        while (true)
        {
            final int startOfBodyLength = offset + START_OF_BODY_LENGTH;
            if (usedBufferData < startOfBodyLength)
            {
                // Need more data
                break;
            }

            try
            {
                if (invalidBodyLengthTag(offset))
                {
                    invalidateMessage(offset);
                    return;
                }

                final int endOfBodyLength = string.scan(startOfBodyLength + 1, usedBufferData, START_OF_HEADER);
                final int startOfChecksumTag = endOfBodyLength + getBodyLength(offset, endOfBodyLength);

                if (!validateBodyLength(startOfChecksumTag))
                {
                    publication.saveMessage(
                        buffer,
                        offset,
                        startOfChecksumTag,
                        UNKNOWN_MESSAGE_TYPE,
                        sessionId,
                        connectionId,
                        INVALID_BODYLENGTH);
                    close();
                    removeEndpoint();
                    break;
                }

                final int startOfChecksumValue = startOfChecksumTag + MIN_CHECKSUM_SIZE;
                final int indexOfLastByteOfMessage = string.scan(startOfChecksumValue, usedBufferData, START_OF_HEADER);
                if (indexOfLastByteOfMessage == UNKNOWN_INDEX)
                {
                    // Need more data
                    break;
                }

                final int messageType = getMessageType(endOfBodyLength, indexOfLastByteOfMessage);
                final int length = (indexOfLastByteOfMessage + 1) - offset;

                final int expectedChecksum = string.getInt(startOfChecksumValue - 1, indexOfLastByteOfMessage);
                final int computedChecksum = string.computeChecksum(offset, startOfChecksumTag + 1);
                //System.out.printf("Computed: %d, Expected: %d\n", computedChecksum, expectedChecksum);
                if (expectedChecksum != computedChecksum)
                {
                    publication.saveMessage(
                        buffer, offset, length, messageType, sessionId, connectionId, INVALID_CHECKSUM);
                }
                else
                {
                    if (sessionId == UNKNOWN)
                    {
                        logon.decode(string, offset, length);
                        final Object compositeKey = sessionIdStrategy.onAcceptorLogon(logon.header());
                        sessionId = sessionIds.onLogon(compositeKey);
                        if (sessionId == DUPLICATE_SESSION)
                        {
                            close();
                        }
                        publication.saveLogon(connectionId, sessionId);
                    }

                    messagesRead.orderedIncrement();
                    publication.saveMessage(buffer, offset, length, messageType, sessionId, connectionId, OK);
                }

                offset += length;
            }
            catch (final Exception ex)
            {
                // TODO: remove exceptions from the common path
                ex.printStackTrace();
                break;
            }
        }

        moveRemainingDataToBufferStart(offset);
    }

    private boolean validateBodyLength(final int startOfChecksumTag)
    {
        return buffer.getByte(startOfChecksumTag) == CHECKSUM0
            && buffer.getByte(startOfChecksumTag + 1) == CHECKSUM1
            && buffer.getByte(startOfChecksumTag + 2) == CHECKSUM2
            && buffer.getByte(startOfChecksumTag + 3) == CHECKSUM3;
    }

    private int getMessageType(final int endOfBodyLength, final int indexOfLastByteOfMessage)
    {
        final int start = string.scan(endOfBodyLength, indexOfLastByteOfMessage, '=');
        if (string.getByte(start + 2) == START_OF_HEADER)
        {
            return string.getByte(start + 1);
        }
        return string.getMessageType(start + 1, 2);
    }

    private int getBodyLength(final int offset, final int endOfBodyLength)
    {
        return string.getNatural(offset + START_OF_BODY_LENGTH, endOfBodyLength);
    }

    private boolean invalidBodyLengthTag(final int offset)
    {
        return string.getDigit(offset + COMMON_PREFIX_LENGTH) != BODY_LENGTH_FIELD ||
               string.getChar(offset + COMMON_PREFIX_LENGTH + 1) != '=';
    }

    private void moveRemainingDataToBufferStart(final int offset)
    {
        usedBufferData -= offset;
        buffer.putBytes(0, buffer, offset, usedBufferData);
        byteBuffer.position(usedBufferData);
    }

    private void invalidateMessage(final int offset)
    {
        DebugLogger.log("%s", buffer, offset, COMMON_PREFIX_LENGTH);
    }

    public void close()
    {
        if (!hasDisconnected)
        {
            closeChannel();
            disconnectEndpoint();
        }
    }

    private void closeChannel()
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // TODO:
            e.printStackTrace();
        }
    }

    private void removeEndpoint()
    {
        framer.removeEndPoint(this);
    }

    private void onDisconnectDetected()
    {
        disconnectEndpoint();
        removeEndpoint();
    }

    private void disconnectEndpoint()
    {
        sessionIds.onDisconnect(connectionId);
        publication.saveDisconnect(connectionId);
        if (selectionKey != null)
        {
            selectionKey.cancel();
        }
        hasDisconnected = true;
    }

    public boolean hasDisconnected()
    {
        return hasDisconnected;
    }

    public void register(final Selector selector) throws IOException
    {
        selectionKey = channel.register(selector, OP_READ, this);
    }

}
