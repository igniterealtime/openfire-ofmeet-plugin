/*
 * Copyright (c) 2017 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.ofmeet;

import org.dom4j.Element;
import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import org.igniterealtime.openfire.plugins.ofmeet.modularity.Module;
import org.igniterealtime.openfire.plugins.ofmeet.modularity.ModuleClassLoader;
import org.jitsi.jicofo.FocusManager;
import org.jitsi.jicofo.JvbDoctor;
import org.jitsi.jicofo.osgi.JicofoBundleConfig;
import org.jitsi.jicofo.xmpp.FocusComponent;
import org.jitsi.meet.OSGi;
import org.jitsi.meet.OSGiBundleConfig;
import org.jivesoftware.openfire.ConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.component.ComponentEventListener;
import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.spi.ConnectionListener;
import org.jivesoftware.openfire.spi.ConnectionManagerImpl;
import org.jivesoftware.openfire.spi.ConnectionType;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;

import java.io.File;
import java.util.*;

/**
 * A wrapper object for the Jitsi Component Focus (jicofo) component.
 *
 * This wrapper can be used to instantiate/initialize and tearing down an instance of the wrapped component. An instance
 * of this class is re-usable.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class JitsiJicofoWrapper implements Module, ComponentEventListener
{
    private static final Logger Log = LoggerFactory.getLogger( JitsiJicofoWrapper.class );

    private String jicofoSubdomain = "focus";

    private FocusComponent jicofoComponent;

    private Thread initThread;

    private Set<JID> jvbComponents = new HashSet<>();

    @Override
    public void initialize( final PluginManager pluginManager, final File pluginDirectory )
    {
        ((InternalComponentManager) ComponentManagerFactory.getComponentManager()).addListener( this );

        ensureFocusUser();

        // The Jitsi Videobridge component must be fully loaded before focus starts to do service discovery.
        initThread = new Thread() {
            @Override
            public void run()
            {
                boolean running = true;
                while ( running )
                {
                    if ( isAcceptingClientConnections() && !jvbComponents.isEmpty() )
                    {
                        try
                        {
                            initializeComponent();
                            return;
                        }
                        catch ( Exception e )
                        {
                            Log.error( "An exception occurred while initializing the Jitsi Jicofo wrapper.", e );
                        }
                    }

                    Log.trace( "Waiting for the server to accept client connections and/or the JVB to become available ..." );
                    try
                    {
                        Thread.sleep( 500 );
                    }
                    catch ( InterruptedException e )
                    {
                        Log.debug( "Interrupted wait for the server to accept client connections and/or the JVB to become available.", e );
                        running = false;
                    }
                }
            }
        };
        initThread.start();
    }

    /**
     * Initialize the wrapped component.
     *
     * @throws Exception On any problem.
     */
    public synchronized void initializeComponent() throws Exception
    {
        Log.debug( "Initializing Jitsi Focus Component (jicofo)...");

        if ( jicofoComponent != null )
        {
            Log.warn( "Another Jitsi Focus Component (jicofo) appears to have been initialized earlier! Unexpected behavior might be the result of this new initialization!" );
        }

        reloadConfiguration();

        final OFMeetConfig config = new OFMeetConfig();

        // Typically, the focus user is a system user (our plugin provisions the user), but if that fails, anonymous authentication will be used.
        final boolean focusAnonymous = config.getFocusPassword() == null;

        // Start the OSGi bundle for Jicofo.
        if ( OSGi.class.getClassLoader() != Thread.currentThread().getContextClassLoader() )
        {
            // the OSGi class should not be present in Openfire itself, or in the parent plugin of these modules. The OSGi implementation does not allow for more than one bundle to be configured/started, which leads to undesired re-used of
            // configuration of one bundle while starting another bundle.
            Log.warn( "The OSGi class is loaded by a class loader different from the one that's loading this module. This suggests that residual configuration is in the OSGi class instance, which is likely to prevent Jicofo from functioning correctly." );
        }
        final OSGiBundleConfig jicofoConfig = new JicofoBundleConfig();
        OSGi.setBundleConfig(jicofoConfig);
        OSGi.setClassLoader( Thread.currentThread().getContextClassLoader() );

        jicofoComponent = new FocusComponent( XMPPServer.getInstance().getServerInfo().getHostname(), 0, XMPPServer.getInstance().getServerInfo().getXMPPDomain(), jicofoSubdomain, null, focusAnonymous, XMPPServer.getInstance().createJID( "focus", null ).toBareJID() );

        Thread.sleep(2000 ); // Intended to prevent ConcurrentModificationExceptions while starting the component. See https://github.com/igniterealtime/ofmeet-openfire-plugin/issues/4
        jicofoComponent.init(); // Note that this is a Jicoco special, not Component#initialize!
        Thread.sleep(2000 ); // Intended to prevent ConcurrentModificationExceptions while starting the component. See https://github.com/igniterealtime/ofmeet-openfire-plugin/issues/4

        ComponentManagerFactory.getComponentManager().addComponent(jicofoSubdomain, jicofoComponent);

        Log.trace( "Successfully initialized Jitsi Focus Component (jicofo).");
    }

    @Override
    public void destroy()
    {
        if ( initThread != null && initThread.isAlive() )
        {
            initThread.interrupt();
            initThread = null;
        }

        ((InternalComponentManager) ComponentManagerFactory.getComponentManager()).removeListener( this );

        try
        {
            Log.debug( "Destroying Jitsi Focus Component..." );

            if ( jicofoComponent == null)
            {
                Log.warn( "Unable to destroy the Jitsi Focus Component, as none appears to be running!" );
            }
            else
            {
                ComponentManagerFactory.getComponentManager().removeComponent(jicofoSubdomain);
                jicofoSubdomain = null;

                jicofoComponent.dispose();
                jicofoComponent = null;
            }

            Log.trace( "Successfully destroyed Jitsi Focus Component. " );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Jicofo wrapper.", ex );
        }
    }

    /**
     * Checks if the server is accepting client connections on the default c2s port.
     *
     * @return true if the server is accepting connections, otherwise false.
     */
    private static boolean isAcceptingClientConnections()
    {
        final ConnectionManager cm = XMPPServer.getInstance().getConnectionManager();
        if ( cm != null )
        {
            final ConnectionManagerImpl cmi = (( ConnectionManagerImpl) cm );
            final ConnectionListener cl = cmi.getListener( ConnectionType.SOCKET_C2S, false );
            return cl != null && cl.getSocketAcceptor() != null;
        }
        return false;
    }

    private static void ensureFocusUser()
    {
        final OFMeetConfig config = new OFMeetConfig();

        // Ensure that the 'focus' user exists.
        final UserManager userManager = XMPPServer.getInstance().getUserManager();
        if ( !userManager.isRegisteredUser( "focus" ) )
        {
            Log.info( "No pre-existing 'focus' user detected. Generating one." );

            String password = config.getFocusPassword();
            if ( password == null || password.isEmpty() )
            {
                password = StringUtils.randomString( 40 );
            }

            try
            {
                userManager.createUser(
                    "focus",
                    password,
                    "Focus User (generated)",
                    null
                );
                config.setFocusPassword( password );
            }
            catch ( Exception e )
            {
                Log.error( "Unable to provision a 'focus' user.", e );
            }
        }

        // Ensure that the 'focus' user can grant permissions in persistent MUCs by making it a sysadmin of the conference service(s).
        final JID focusUserJid = new JID( "focus@" + XMPPServer.getInstance().getServerInfo().getXMPPDomain() );
        for ( final MultiUserChatService mucService : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices() )
        {
            if ( !mucService.isSysadmin( focusUserJid ) )
            {
                Log.info( "Adding 'focus' user as a sysadmin to the '{}' MUC service.", mucService.getServiceName() );
                mucService.addSysadmin( focusUserJid );
            }
        }
    }

    @Override
    public void reloadConfiguration()
    {
        final OFMeetConfig config = new OFMeetConfig();

        System.setProperty( FocusManager.HOSTNAME_PNAME, XMPPServer.getInstance().getServerInfo().getHostname() );
        System.setProperty( FocusManager.XMPP_DOMAIN_PNAME, XMPPServer.getInstance().getServerInfo().getXMPPDomain() );
        System.setProperty( FocusManager.FOCUS_USER_DOMAIN_PNAME, XMPPServer.getInstance().getServerInfo().getXMPPDomain() );
        if ( config.getFocusPassword() == null )
        {
            Log.warn( "No password is configured for the 'focus'. This is likely going to cause problems in webRTC meetings." );
        }
        else
        {
            System.setProperty( FocusManager.FOCUS_USER_NAME_PNAME, "focus" );
            System.setProperty( FocusManager.FOCUS_USER_PASSWORD_PNAME, config.getFocusPassword() );
        }

        // Jicofo should trust any certificates (as it is communicating with the local Openfire instance only, which we can safely define as 'trusted').
        System.setProperty( "net.java.sip.communicator.service.gui.ALWAYS_TRUST_MODE_ENABLED", Boolean.toString( true ) );
        System.setProperty( FocusManager.ALWAYS_TRUST_PNAME, Boolean.toString( true ) );

        // Disable health check. Our JVB is not an external component, so there's no need to check for its connectivity.
        // Also, the health check appears to cumulatively use and not release resources!
        System.setProperty( JvbDoctor.HEALTH_CHECK_INTERVAL_PNAME, "-1" );
        System.setProperty( "org.jitsi.jicofo.PING_INTERVAL", "-1" );

        // Disable JVB rediscovery. We are running with one hard-coded videobridge, there's no need for dynamic detection of others.
        System.setProperty( "org.jitsi.jicofo.SERVICE_REDISCOVERY_INTERVAL", "-1" ); // Aught to use a reference to ComponentsDiscovery.REDISCOVERY_INTERVAL_PNAME, but that constant is private.
    }

    @Override
    public Map<String, String> getServlets()
    {
        return Collections.emptyMap();
    }

    @Override
    public void componentRegistered( final JID componentJID )
    {
        Log.trace( "Component registered: {} ", componentJID );
    }

    @Override
    public void componentUnregistered( final JID componentJID )
    {
        Log.trace( "Component unregistered: {} ", componentJID );
        if ( jvbComponents.remove( componentJID ) )
        {
            Log.info( "A Jitsi Videobridge component {} went offline.", componentJID );
        }
    }

    @Override
    public void componentInfoReceived( final IQ iq )
    {
        Log.trace( "Component info received: {} ", iq );
        final Iterator<Element> iterator = iq.getChildElement().elementIterator( "identity" );
        while ( iterator.hasNext() )
        {
            if ( "JitsiVideobridge".equals( iterator.next().attributeValue( "name" )) )
            {
                Log.info( "Detected a Jitsi Videobridge component: {}.", iq.getFrom() );
                jvbComponents.add( iq.getFrom() );
            }
        }
    }
}
