package org.cloudburstmc.netty.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.channel.unix.IntegerUnixChannelOption;

import java.lang.reflect.Field;
import java.net.SocketOption;

public class IpDontFragmentProvider {
    private static final ChannelOption<?> IP_DONT_FRAGMENT_OPTION;
    private static final Object IP_DONT_FRAGMENT_VALUE;

    static {
        ChannelOption<?> ipDontFragmentOption = null;
        Object ipDontFragmentValue = null;
        
        setterBlock: {
            // Windows and Linux Compatible (Java 19+)
            try {
                Class<?> c = Class.forName("jdk.net.ExtendedSocketOptions");
                Field f = c.getField("IP_DONTFRAGMENT");
    
                ipDontFragmentOption = NioChannelOption.of((SocketOption<?>) f.get(null));
                ipDontFragmentValue = true;
                break setterBlock;
            } catch (ClassNotFoundException | NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
                
            }

            // Unix Compatible (Java 8+)
            ipDontFragmentOption = new IntegerUnixChannelOption("IP_DONTFRAG", 0 /* IPPROTO_IP */, 10 /* IP_MTU_DISCOVER */);
            ipDontFragmentValue = 2 /* IP_PMTUDISC_DO */;
            break setterBlock;
        }

        IP_DONT_FRAGMENT_OPTION = ipDontFragmentOption;
        IP_DONT_FRAGMENT_VALUE = ipDontFragmentValue;
    }

    @SuppressWarnings("unchecked")
    public static <T> boolean trySet(Channel channel) {
        if (IP_DONT_FRAGMENT_OPTION == null) return false;
        return channel.config().setOption((ChannelOption<T>) IP_DONT_FRAGMENT_OPTION, (T) IP_DONT_FRAGMENT_VALUE);
    }
}