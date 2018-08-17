/*
 * Copyright (C) 2018 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.ofmeet.videobridge;

import org.ice4j.ice.harvest.MappingCandidateHarvesters;
import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import org.igniterealtime.openfire.plugins.ofmeet.modularity.Module;
import org.igniterealtime.openfire.plugins.ofmeet.modularity.ModuleClassLoader;
import org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext;
import org.jivesoftware.openfire.container.PluginServlet;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.openfire.PluginImpl;
import org.jivesoftware.openfire.container.PluginManager;

import javax.servlet.GenericServlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A wrapper object for the Jitsi Videobridge Openfire plugin.
 *
 * This wrapper can be used to instantiate/initialize and tearing down an instance of that plugin. An instance of this
 * class is re-usable.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class JvbPluginWrapper implements Module
{
    private static final Logger Log = LoggerFactory.getLogger(JvbPluginWrapper.class);

    private PluginImpl jitsiPlugin;

    /**
     * Initialize the wrapped component.
     */
    @Override
    public synchronized void initialize( final PluginManager manager, final File pluginDirectory )
    {
        Log.debug( "Initializing Jitsi Videobridge..." );

        if ( jitsiPlugin != null )
        {
            Log.warn( "Another Jitsi Videobridge appears to have been initialized earlier! Unexpected behavior might be the result of this new initialization!" );
        }

        reloadConfiguration();

        // Disable health check. Our JVB is not an external component, so there's no need to check for its connectivity.
        System.setProperty( "org.jitsi.videobridge.PING_INTERVAL", "-1" );

        jitsiPlugin = new PluginImpl();
        jitsiPlugin.initializePlugin( manager, pluginDirectory );

        Log.trace( "Successfully initialized Jitsi Videobridge." );
    }

    /**
     * Destroying the wrapped component. After this call, the wrapped component can be re-initialized.
     *
     * @throws Exception On any problem.
     */
    @Override
    public synchronized void destroy()
    {
        Log.debug( "Destroying Jitsi Videobridge..." );

        if ( jitsiPlugin == null )
        {
            Log.warn( "Unable to destroy the Jitsi Videobridge, as none appears to be running!" );
        }

        jitsiPlugin.destroyPlugin();
        jitsiPlugin = null;
        Log.trace( "Successfully destroyed Jitsi Videobridge." );
    }

    /**
     * Jitsi takes most of its configuration through system properties. This method sets these
     * properties, using values defined in JiveGlobals.
     */
    @Override
    public void reloadConfiguration()
    {
        if ( JiveGlobals.getProperty( SRTPCryptoContext.CHECK_REPLAY_PNAME ) != null )
        {
            System.setProperty( SRTPCryptoContext.CHECK_REPLAY_PNAME, JiveGlobals.getProperty( SRTPCryptoContext.CHECK_REPLAY_PNAME ) );
        }

        // Set up the NAT harvester, but only when needed.
        final InetAddress natPublic = new OFMeetConfig().getPublicNATAddress();
        if ( natPublic == null )
        {
            System.clearProperty( MappingCandidateHarvesters.NAT_HARVESTER_PUBLIC_ADDRESS_PNAME );
        }
        else
        {
            System.setProperty( MappingCandidateHarvesters.NAT_HARVESTER_PUBLIC_ADDRESS_PNAME, natPublic.getHostAddress() );
        }

        final InetAddress natLocal = new OFMeetConfig().getLocalNATAddress();
        if ( natLocal == null )
        {
            System.clearProperty( MappingCandidateHarvesters.NAT_HARVESTER_LOCAL_ADDRESS_PNAME );
        }
        else
        {
            System.setProperty( MappingCandidateHarvesters.NAT_HARVESTER_LOCAL_ADDRESS_PNAME, natLocal.getHostAddress() );
        }

        final List<String> stunMappingHarversterAddresses = new OFMeetConfig().getStunMappingHarversterAddresses();
        if ( stunMappingHarversterAddresses == null || stunMappingHarversterAddresses.isEmpty() )
        {
            System.clearProperty( MappingCandidateHarvesters.STUN_MAPPING_HARVESTER_ADDRESSES_PNAME );
        }
        else
        {
            // Concat into comma-separated string.
            final StringBuilder sb = new StringBuilder();
            for ( final String address : stunMappingHarversterAddresses )
            {
                sb.append( address );
                sb.append( "," );
            }
            System.setProperty( MappingCandidateHarvesters.STUN_MAPPING_HARVESTER_ADDRESSES_PNAME, sb.substring( 0, sb.length() - 1 ) );
        }

        // allow videobridge access without focus
        System.setProperty( Videobridge.DEFAULT_OPTIONS_PROPERTY_NAME, "2" );
    }

    @Override
    public Map<String, String> getServlets()
    {
        final Map<String, String> servlets = new ConcurrentHashMap<>(  );
        servlets.put( "/jitsi-videobridge.jsp", "org.jivesoftware.openfire.plugin.jitsivideobridge.jitsi_002dvideobridge_jsp" );

        return servlets;
    }
}
