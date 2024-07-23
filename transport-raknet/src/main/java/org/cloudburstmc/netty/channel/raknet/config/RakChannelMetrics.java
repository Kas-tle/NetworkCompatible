/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.raknet.RakState;

public interface RakChannelMetrics {

    default void bytesIn(Channel channel, int count) {
    }

    default void bytesOut(Channel channel, int count) {
    }

    default void rakDatagramsIn(Channel channel, int count) {
    }

    default void rakDatagramsOut(Channel channel, int count) {
    }

    default void encapsulatedIn(Channel channel, int count) {
    }

    default void encapsulatedOut(Channel channel, int count) {
    }

    default void rakStaleDatagrams(Channel channel, int count) {
    }

    default void ackIn(Channel channel, int count) {
    }

    default void ackOut(Channel channel, int count) {
    }

    default void nackIn(Channel channel, int count) {
    }

    default void nackOut(Channel channel, int count) {
    }

    default void stateChange(Channel channel, RakState state) {
    }
}
