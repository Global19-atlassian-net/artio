/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine;

import org.agrona.DirectBuffer;

/**
 * A callback that can be implemented to inspect the messages that get retransmitted on an Ilink3 connection.
 *
 * This callback is called for every message that needs to be replayed, even those that are replaced with
 * a sequence message. The handler is invoked on the Replay Agent.
 */
@FunctionalInterface
public interface ILink3RetransmitHandler
{

    /**
     * Callback for receiving iLink3 business messages. Details of business messages can be found in the
     * <a href="https://www.cmegroup.com/confluence/display/EPICSANDBOX/iLink+3+Application+Layer">CME
     * Documentation</a>. These may also be referred to as application layer messages.
     *
     * @param templateId the templateId of the iLink3 SBE message that you have received.
     * @param buffer the buffer containing the message.
     * @param offset the offset within the buffer at which your message starts.
     * @param blockLength the blockLength of the received message.
     * @param version the sbe version of the protocol.
     */
    void onReplayedBusinessMessage(
        int templateId,
        DirectBuffer buffer,
        int offset,
        int blockLength,
        int version);
}
