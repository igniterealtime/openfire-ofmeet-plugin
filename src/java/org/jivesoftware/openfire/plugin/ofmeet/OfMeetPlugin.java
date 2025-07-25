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
import org.eclipse.jetty.ee8.webapp.WebAppContext;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee8.websocket.servlet.*;
import org.eclipse.jetty.ee8.websocket.server.*;
import org.eclipse.jetty.ee8.websocket.server.config.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.muc.MUCEventListener;
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
import java.util.concurrent.CountDownLatch;

import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import java.util.concurrent.*;
import org.ifsoft.websockets.*;


/**
 * Bundles various Jitsi components into one, standalone Openfire plugin.
 */
public class OfMeetPlugin implements Plugin, SessionEventListener, ClusterEventListener, PropertyEventListener, MUCEventListener
{
    private static final Logger Log = LoggerFactory.getLogger(OfMeetPlugin.class);
    public static OfMeetPlugin self;	

    private PluginManager manager;
    public File pluginDirectory;
    public boolean restartNeeded = false;	

    private OfMeetIQHandler ofmeetIQHandler;
    private WebAppContext publicWebApp;
    private JitsiJvbWrapper jitsiJvbWrapper;
    private JitsiJicofoWrapper jitsiJicofoWrapper;

    private final OFMeetConfig config;		
	private ComponentManager componentManager;
    private FocusComponent focusComponent = null;
    private ServletContextHandler jvbWsContext = null;	

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
			JID jid = new JID("admin@" + domain);
			MUCRoom controlRoom = mucService.getChatRoom(roomName, jid);
			controlRoom.setPersistent(false);
			controlRoom.setPublicRoom(true);
			controlRoom.unlock(controlRoom.getSelfRepresentation().getAffiliation());
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
			
            ofmeetIQHandler = new OfMeetIQHandler();
            XMPPServer.getInstance().getIQRouter().addHandler(ofmeetIQHandler);
			XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature("urn:xmpp:http:online-meetings:initiate:0");				
			XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature("urn:xmpp:http:online-meetings#jitsi");			
			
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
            XMPPServer.getInstance().getIQRouter().removeHandler(ofmeetIQHandler);			
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
        publicWebApp.getMimeTypes().addMimeMapping("wasm", "application/wasm");
				
        HttpBindManager.getInstance().addJettyHandler( publicWebApp );
		
        jvbWsContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
        jvbWsContext.setContextPath("/colibri-ws");

        JettyWebSocketServletContainerInitializer.configure(jvbWsContext, (servletContext, wsContainer) ->
        {
            wsContainer.setMaxTextMessageSize(65535);
            wsContainer.addMapping("/*", new JvbSocketCreator());
        });	

		HttpBindManager.getInstance().addJettyHandler( jvbWsContext );

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

				HttpBindManager.getInstance().removeJettyHandler(jvbWsContext);
				jvbWsContext.destroy();				
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

    public URL getWebappURL()
    {
        final String override = JiveGlobals.getProperty( "ofmeet.webapp.url.override" );
        if ( override != null && !override.trim().isEmpty() )
        {
            try
            {
                return new URL( override );
            }
            catch ( MalformedURLException e )
            {
                Log.warn( "An override for the webapp address is defined in 'ofmeet.webapp.url.override', but its value is not a valid URL.", e );
            }
        }
        try
        {
            final String protocol = "https"; // No point in providing the non-SSL protocol, as webRTC won't work there.
            final String host = XMPPServer.getInstance().getServerInfo().getHostname();
            final int port = Integer.parseInt(JiveGlobals.getProperty("httpbind.port.secure", "7443"));
            final String path;
            if ( publicWebApp != null )
            {
                path = publicWebApp.getContextPath();
            }
            else
            {
                path = new OFMeetConfig().getWebappContextPath();
            }

            return new URL( protocol, host, port, path );
        }
        catch ( MalformedURLException e )
        {
            Log.error( "Unable to compose the webapp URL", e );
            return null;
        }
    }
	
    public String getIpAddress()
    {
        String ourHostname = XMPPServer.getInstance().getServerInfo().getHostname();
        String ourIpAddress = JiveGlobals.getXMLProperty("network.interface");
		
		if (ourIpAddress == null) {
			ourIpAddress = "127.0.0.1";
			
			try {
				ourIpAddress = InetAddress.getByName(ourHostname).getHostAddress();
			} catch (Exception e) {

			}
		}

        return ourIpAddress;
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

    public void roomCreated(JID roomJID)
    {

    }

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
	
    public void roomClearChatHistory(long roomID, JID roomJID) {

    }

    public void roomCreated(long roomID, JID roomJID) {

    }
	
    public void roomDestroyed(long roomID, JID roomJID) {

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
    //-------------------------------------------------------
    //
    //      WebSocket Handler
    //
    //-------------------------------------------------------
	
	public class JvbSocketCreator implements JettyWebSocketCreator
	{		
        @Override public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
        {
			String ipaddr = JiveGlobals.getProperty( "ofmeet.videobridge.rest.host", OfMeetPlugin.self.getIpAddress());	
            String jvbPort = JiveGlobals.getProperty( "ofmeet.websockets.plainport", "8180");

            HttpServletRequest request = req.getHttpServletRequest();
            String path = request.getRequestURI();
            String query = request.getQueryString();
            List<String> protocols = new ArrayList<String>();

            for (String subprotocol : req.getSubProtocols())
            {
                Log.debug("WSocketCreator found protocol " + subprotocol);
                resp.setAcceptedSubProtocol(subprotocol);
                protocols.add(subprotocol);
            }

            if (query != null) path += "?" + query;

            Log.debug("JvbSocketCreator " + path + " " + query);
            String url = "ws://" + ipaddr + ":" + jvbPort + path;

            ProxyWebSocket socket = null;
            ProxyConnection proxyConnection = new ProxyConnection(URI.create(url), protocols, 10000);

            socket = new ProxyWebSocket();
            socket.setProxyConnection(proxyConnection);
            return socket;
        }		
	}	
}
