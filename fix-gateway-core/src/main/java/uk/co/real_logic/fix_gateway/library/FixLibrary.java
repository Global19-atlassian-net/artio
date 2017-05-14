/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.library;

import org.agrona.IoUtil;
import org.agrona.concurrent.SystemEpochClock;
import uk.co.real_logic.fix_gateway.CommonConfiguration;
import uk.co.real_logic.fix_gateway.FixGatewayException;
import uk.co.real_logic.fix_gateway.GatewayProcess;
import uk.co.real_logic.fix_gateway.Reply;
import uk.co.real_logic.fix_gateway.messages.SessionReplyStatus;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.timing.LibraryTimers;

import java.io.File;
import java.util.List;

import static uk.co.real_logic.fix_gateway.dictionary.generation.Exceptions.closeAll;

/**
 * FIX Library instances represent a process in the gateway where session management,
 * message parsing and API users configure the gateway.
 * <p>
 * Libraries can be run in the same process as the engine, or in a
 * different process.
 * <p>
 * FixLibrary instances are not thread safe and should be run on
 * their own thread.
 *
 * @see uk.co.real_logic.fix_gateway.engine.FixEngine
 */
public class FixLibrary extends GatewayProcess
{
    public static final int NO_MESSAGE_REPLAY = -1;

    private final LibraryConfiguration configuration;
    private final LibraryScheduler scheduler;
    private final LibraryPoller poller;
    private boolean isPolling = false;

    FixLibrary(final LibraryConfiguration configuration)
    {
        this.configuration = configuration;
        scheduler = configuration.scheduler();
        configuration.conclude();

        try
        {
            init(configuration);
            final LibraryTimers timers = new LibraryTimers(configuration.nanoClock());
            initMonitoringAgent(timers.all(), configuration);

            final LibraryTransport transport = new LibraryTransport(configuration, fixCounters, aeron);
            poller = new LibraryPoller(
                configuration, timers, fixCounters, transport, this, new SystemEpochClock());
        }
        catch (final Exception e)
        {
            try
            {
                closeAnythingHoldingFileHandles();
                deleteFiles();
            }
            catch (final Exception innerException)
            {
                innerException.addSuppressed(e);
                throw innerException;
            }
            throw e;
        }
    }

    private void closeAnythingHoldingFileHandles()
    {
        if (monitoringAgent == null)
        {
            monitoringFile.close();
        }
        else
        {
            monitoringAgent.onClose();
        }
    }

    private FixLibrary connect()
    {
        poller.startConnecting();
        scheduler.launch(configuration, errorHandler, monitoringAgent);
        return this;
    }

    // ------------- Public API -------------

    /**
     * Start connecting to an engine. This method returns a FixLibrary immediately even if it hasn't connected.
     *
     * You should call {@link #poll(int)} on a regular duty cycle until the connection completes.
     * {@link #isConnected()} can be polled in order to determine whether library is connected. Also the
     * {@link LibraryConnectHandler#onConnect(FixLibrary)} method will be invoked.
     *
     * @param configuration the configuration for this library instance.
     * @return the library instance.
     * @throws FixGatewayException
     *         if there's an error connecting to the FIX Gateway.
     */
    public static FixLibrary connect(final LibraryConfiguration configuration)
    {
        return new FixLibrary(configuration).connect();
    }

    /**
     * Poll the library all of its component sessions to process any messages
     * and events that have received from or should be sent to the engine.
     *
     * @param fragmentLimit the maximum number of events to read from the engine.
     * @return 0 if no work was performed, > 0 otherwise.
     */
    public int poll(final int fragmentLimit)
    {
        isPolling = true;
        try
        {
            return poller.poll(fragmentLimit);
        }
        finally
        {
            isPolling = false;
        }
    }

    /**
     * Check if the library is connected to an engine.
     * <p>
     * Note that this refers to whether a library is connected to a FIX Engine,
     * not whether of its sessions are connected.
     *
     * @return true if the library is connected to an engine, false otherwise.
     * @see Session#isConnected()
     * @see uk.co.real_logic.fix_gateway.engine.FixEngine
     */
    public boolean isConnected()
    {
        return poller.isConnected();
    }

    public boolean isClosed()
    {
        return poller.isClosed();
    }

    /**
     * Get the identifier of the library.
     *
     * @return the identifier of the library.
     */
    public int libraryId()
    {
        return poller.libraryId();
    }

    /**
     * Get a list of the currently active sessions.
     * <p>
     * Note: the list is unmodifiable.
     *
     * @return a list of the currently active sessions.
     */
    public List<Session> sessions()
    {
        return poller.sessions();
    }

    /**
     * Close the Library. This will also remove all files associated with the library.
     */
    public void close()
    {
        if (isPolling)
        {
            throw new IllegalArgumentException("You cannot close the library in the middle of a poll");
        }

        internalClose();
    }

    void internalClose()
    {
        closeAll(poller, scheduler, super::close, this::deleteFiles);
    }

    private void deleteFiles()
    {
        removeParentDirectory(configuration.histogramLoggingFile());
        removeParentDirectory(configuration.monitoringFile());
    }

    private void removeParentDirectory(final String path)
    {
        final File parentFile = new File(path).getParentFile();
        if (parentFile.exists())
        {
            IoUtil.delete(parentFile, true);
        }
    }

    /**
     * Initiate a FIX session with a FIX acceptor. This method returns a reply object
     * wrapping the Session itself.
     *
     * @param configuration the configuration to use for the session.
     * @return the session object for the session that you've initiated. It can return the following errors:
     *         {@link IllegalStateException}
     *         if you're trying to initiate two sessions at the same time or if there's a timeout talking to
     *         the {@link uk.co.real_logic.fix_gateway.engine.FixEngine}.
     *         This probably indicates that there's a problem in your code or that your engine isn't running.
     *         {@link FixGatewayException}
     *         if you're unable to connect to the accepting gateway.
     *         This probably indicates a configuration problem related to the external gateway.
     */
    public Reply<Session> initiate(final SessionConfiguration configuration)
    {
        return poller.initiate(configuration);
    }

    /**
     * Release this session object to the gateway to manage. If the release
     * operation has successfully completed then it will return {@link SessionReplyStatus#OK}.
     *
     * Similar to {@link this#initiate(SessionConfiguration)} this is a non-blocking operation that
     * returns a reply object that indicates what has happened to its result.
     *
     * @param session the session to release
     * @param timeoutInMs the timeout for this operation
     * @return the result of this operation.
     */
    public Reply<SessionReplyStatus> releaseToGateway(final Session session, final long timeoutInMs)
    {
        CommonConfiguration.validateTimeout(timeoutInMs);
        return poller.releaseToGateway(session, timeoutInMs);
    }

    /**
     * Request a session be acquired from the Gateway. It returns a {@link LibraryReply} object.
     *
     * If this session is being managed by
     * the gateway then your {@link SessionAcquireHandler} will receive a callback
     * and the reply will be {@link SessionReplyStatus#OK}. You may also receive the reply of
     * {@link SessionReplyStatus#SEQUENCE_NUMBER_TOO_HIGH} if the sequence number you have passed in
     * is higher than the current sequence number known by the engine. This may happen to a sequence reset.
     * In this case you will still get the callback to the {@link SessionAcquireHandler} but won't get a
     * replay on any messages.
     *
     * If another library has acquired the session then this method will return
     * {@link SessionReplyStatus#OTHER_SESSION_OWNER}. If the connection id refers
     * to an unknown session then the method returns {@link SessionReplyStatus#UNKNOWN_SESSION}.
     * If this library instance is unknown to the gateway, for example if its heartbeating
     * mechanism has timed out due to {@link this#poll(int)} not being called often enough.
     *
     * @param sessionId the id of the session to acquire.
     * @param lastReceivedSequenceNumber the last received message sequence number
     *                                   that you know about. You will get a stream
     *                                   of messages replayed to you from
     *                                   <code>lastReceivedMessageSequenceNumber + 1</code>
     *                                   to the latest message sequence number.
     *                                   If you don't care about message replay then
     *                                   use {@link FixLibrary#NO_MESSAGE_REPLAY} as the parameter.
     * @param sequenceIndex the index of the sequence within which the lastReceivedSequenceNumber
     *                      refers. If you don't care about message replay then use
     *                      {@link FixLibrary#NO_MESSAGE_REPLAY} as the parameter.
     * @param timeoutInMs the timeout for this operation
     * @return the reply object representing the result of the request.
     */
    public Reply<SessionReplyStatus> requestSession(
        final long sessionId,
        final int lastReceivedSequenceNumber,
        final int sequenceIndex,
        final long timeoutInMs)
    {
        CommonConfiguration.validateTimeout(timeoutInMs);
        return poller.requestSession(sessionId, lastReceivedSequenceNumber, sequenceIndex, timeoutInMs);
    }

    public String currentAeronChannel()
    {
        return poller.currentAeronChannel();
    }

}
