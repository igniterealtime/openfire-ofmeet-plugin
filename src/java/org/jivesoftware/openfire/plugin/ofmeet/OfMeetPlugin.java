/*
 * $Revision $
 * $Date $
 *
 * Copyright (C) 2005-2010 Jive Software. All rights reserved.
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

package org.jivesoftware.openfire.plugin.ofmeet;

import org.dom4j.Element;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlets.*;
import org.eclipse.jetty.servlet.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.MUCEventListener;
import org.jivesoftware.openfire.muc.MUCRole;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.event.SessionEventDispatcher;
import org.jivesoftware.openfire.event.SessionEventListener;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.security.SecurityAuditManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Presence;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import java.util.concurrent.*;


/**
 * Bundles various Jitsi components into one, standalone Openfire plugin.
 */
public class OfMeetPlugin implements Plugin, SessionEventListener, ClusterEventListener, PropertyEventListener, MUCEventListener
{
    private static final Logger Log = LoggerFactory.getLogger(OfMeetPlugin.class);
    public static OfMeetPlugin self;	

    private PluginManager manager;
    public File pluginDirectory;

    private WebAppContext publicWebApp;
    private JitsiJvbWrapper jitsiJvbWrapper;
    private JitsiJicofoWrapper jitsiJicofoWrapper;

    private final OFMeetConfig config;		
	private ComponentManager componentManager;
    private FocusComponent focusComponent = null;	

    public OfMeetPlugin()
    {
        config = new OFMeetConfig();		
    }

    public String getName()
    {
        return "ofmeet";
    }

    public String getConferenceStats()
    {
		if (jitsiJvbWrapper == null) return null;
        return jitsiJvbWrapper.getConferenceStats();
    }

    public String getJvbDuration()
    {
        long current_time = System.currentTimeMillis();
        String duration = "";

        try {
            long start_timestamp = Long.parseLong(System.getProperty("ofmeet.jvb.started.timestamp", String.valueOf(System.currentTimeMillis())));
            duration = StringUtils.getFullElapsedTime(System.currentTimeMillis() - start_timestamp);
        } catch (Exception e) {}

        return duration;
    }

    public String getDescription()
    {
        return "OfMeet Plugin";
    }
	
	private void createConference(final String roomName)
	{
        try
        {			
			final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
			MultiUserChatService mucService = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService("conference");	
			MUCRoom controlRoom = mucService.getChatRoom(roomName, new JID("admin@" + domain));
			controlRoom.setPersistent(false);
			controlRoom.setPublicRoom(true);
			controlRoom.unlock(controlRoom.getRole());
			mucService.syncChatRoom(controlRoom);	
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to create conference " + roomName, ex );
        }			
	}
	
	private void setupJvb()
	{
        try
        {	
			jitsiJvbWrapper = new JitsiJvbWrapper();		
			ensureJvbUser();
			createConference("ofmeet");			
			jitsiJvbWrapper.initialize( manager, pluginDirectory );			
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize jicofo", ex );
        }						
	}
	
	private void setupJicofo()
	{
        try
        {	
			if ( JiveGlobals.getBooleanProperty( "ofmeet.use.internal.focus.component", true ) )
			{
				focusComponent = new FocusComponent();
				componentManager = ComponentManagerFactory.getComponentManager();			
				componentManager.addComponent("focus", focusComponent);		
			}
			
			jitsiJicofoWrapper = new JitsiJicofoWrapper();		
			ensureFocusUser();
			jitsiJicofoWrapper.initialize(pluginDirectory);			
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize jicofo", ex );
        }			
	}	

    public void initializePlugin(final PluginManager manager, final File pluginDirectory)
    {
		self = this;	

        this.manager = manager;
        this.pluginDirectory = pluginDirectory;	

        // Initialize all Jitsi software, which provided the video-conferencing functionality.

        try
        {
			if (!ClusterManager.isClusteringEnabled())
			{				
				setupJvb();
				setupJicofo();
			}

			ClusterManager.addListener(this);

        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize Jitsi", ex );
        }			
				
        try
        {
            loadPublicWebApp();
            SessionEventDispatcher.addListener(this);
            PropertyEventDispatcher.addListener(this);
            MUCEventDispatcher.addListener(this);
			
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize pade extensions", ex );
        }		
    }

    public void destroyPlugin()
    {		
        try
        {
            SessionEventDispatcher.removeListener(this);
            PropertyEventDispatcher.removeListener( this );
            MUCEventDispatcher.removeListener(this);

            unloadPublicWebApp();		
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to unload the public web application of OFMeet.", ex );
        }


        try
        {
            if (jitsiJvbWrapper != null) 	jitsiJvbWrapper.destroy();
            if (jitsiJicofoWrapper != null) jitsiJicofoWrapper.destroy();
			if (focusComponent != null) componentManager.removeComponent("focus");				
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Videobridge plugin wrapper.", ex );
        }

        ClusterManager.removeListener(this);
		
		self = null;		
    }

    protected void loadPublicWebApp() throws Exception
    {
        publicWebApp = new WebAppContext(null, pluginDirectory.getPath() + "/classes/jitsi-meet",  new OFMeetConfig().getWebappContextPath());
        publicWebApp.setClassLoader(this.getClass().getClassLoader());
		
        final List<ContainerInitializer> initializers = new ArrayList<>();
        initializers.add(new ContainerInitializer(new JettyJasperInitializer(), null));
        publicWebApp.setAttribute("org.eclipse.jetty.containerInitializers", initializers);
        publicWebApp.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());
        publicWebApp.setWelcomeFiles(new String[]{"index.html"});
		
        HttpBindManager.getInstance().addJettyHandler( publicWebApp );

        Log.debug( "Initialized public web application", publicWebApp.toString() );
    }	


    public void unloadPublicWebApp() throws Exception
    {
        if ( publicWebApp != null )
        {
            try
            {
                HttpBindManager.getInstance().removeJettyHandler( publicWebApp );
                publicWebApp.destroy();				
            }
            finally
            {
                publicWebApp = null;
            }
        }
    }

	private void ensureJvbUser()
    {
		final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();		
        final UserManager userManager = XMPPServer.getInstance().getUserManager();
        String username =  config.getJvbName();
		
		if (ClusterManager.isClusteringEnabled()) {	
			username = username + JiveGlobals.getXMLProperty("ofmeet.octo_id", "1");
		}		

        if ( !userManager.isRegisteredUser( new JID(username + "@" + domain), false ) )
        {
            Log.info( "No pre-existing '" + username + "' user detected. Generating one." );
            String password = config.getJvbPassword();

            if ( password == null || password.isEmpty() )
            {
                password = StringUtils.randomString( 40 );
            }

            try
            {
                userManager.createUser( username, password, "JVB User (generated)", null);
                config.setJvbPassword( password );
            }
            catch ( Exception e )
            {
                Log.error( "Unable to provision a 'jvb' user.", e );
            }
        }
    }
	
    private void ensureFocusUser()
    {
		final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();	
        final UserManager userManager = XMPPServer.getInstance().getUserManager();
		
        if ( !userManager.isRegisteredUser( new JID("focus@" + domain), false ) )
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

        if ( JiveGlobals.getBooleanProperty( "ofmeet.conference.admin", true ) )
        {
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
    }


    //-------------------------------------------------------
    //
    //      clustering
    //
    //-------------------------------------------------------

	private void terminateJisti()
	{
        try
        {
            if (jitsiJvbWrapper != null) 	jitsiJvbWrapper.destroy();
            if (jitsiJicofoWrapper != null) jitsiJicofoWrapper.destroy();
			if (focusComponent != null) 	componentManager.removeComponent("focus");				
			
			if (jitsiJvbWrapper != null) Thread.sleep(60000);	// don't wait on first time
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy Jitsi components.", ex );
        }		
	}
	
    @Override
    public void joinedCluster()
    {
        Log.info("OfMeet Plugin - joinedCluster");			
		terminateJisti();
		
		setupJvb();		
    }

    @Override
    public void joinedCluster(byte[] arg0)
    {
    }

    @Override
    public void leftCluster()
    {
        Log.info("OfMeet Plugin - leftCluster");
		terminateJisti();
		
		setupJvb();
		setupJicofo();			
    }

    @Override
    public void leftCluster(byte[] arg0)
    {
    }

    @Override
    public void markedAsSeniorClusterMember()
    {
        Log.info("OfMeet Plugin - markedAsSeniorClusterMember");
		setupJicofo();	
    }

    public void setRecording(String roomName, String path)
    {

    }

    //-------------------------------------------------------
    //
    //      MUC room events
    //
    //-------------------------------------------------------

    @Override
    public void roomCreated(JID roomJID)
    {

    }

    @Override
    public void roomDestroyed(JID roomJID)
    {
				
    }

    @Override
    public void occupantJoined(final JID roomJID, JID user, String nickname)
    {

    }

    @Override
    public void occupantLeft(final JID roomJID, JID user, String nickname)
    {

    }

	
	@Override
	public void occupantNickKicked(JID roomJID, String nickname)
	{
		
	}
	
    @Override
    public void nicknameChanged(JID roomJID, JID user, String oldNickname, String newNickname)
    {

    }

    @Override
    public void messageReceived(JID roomJID, JID user, String nickname, Message message)
    {
		
    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID user, String newSubject)
    {

    }

    @Override
    public void privateMessageRecieved(JID a, JID b, Message message)
    {

    }
	
    //-------------------------------------------------------
    //
    //      session management
    //
    //-------------------------------------------------------

    public void anonymousSessionCreated(Session session)
    {
        Log.debug("OfMeet Plugin -  anonymousSessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void anonymousSessionDestroyed(Session session)
    {
        Log.debug("OfMeet Plugin -  anonymousSessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void resourceBound(Session session)
    {
        Log.debug("OfMeet Plugin -  resourceBound "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void sessionCreated(Session session)
    {
        Log.debug("OfMeet Plugin -  sessionCreated "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    public void sessionDestroyed(Session session)
    {
        Log.debug("OfMeet Plugin -  sessionDestroyed "+ session.getAddress().toString() + "\n" + ((ClientSession) session).getPresence().toXML());
    }

    //-------------------------------------------------------
    //
    //      property management
    //
    //-------------------------------------------------------

    @Override
    public void propertySet( String s, Map map )
    {

    }

    @Override
    public void propertyDeleted( String s, Map map )
    {

    }

    @Override
    public void xmlPropertySet( String s, Map map )
    {

    }

    @Override
    public void xmlPropertyDeleted( String s, Map map )
    {

    }
}
