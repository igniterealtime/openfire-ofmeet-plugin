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
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.WebAppContext;
import org.ice4j.ice.harvest.MappingCandidateHarvesters;
import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import org.jitsi.impl.neomedia.transform.srtp.SRTPCryptoContext;
import org.jitsi.videobridge.Videobridge;
import org.jitsi.videobridge.Conference;
import org.jitsi.videobridge.Content;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.event.SessionEventDispatcher;
import org.jivesoftware.openfire.event.SessionEventListener;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.net.SASLAuthentication;
import org.jivesoftware.openfire.plugin.ofmeet.jetty.OfMeetAzure;
import org.jivesoftware.openfire.plugin.ofmeet.jetty.OfMeetLoginService;
import org.jivesoftware.openfire.plugin.ofmeet.sasl.OfMeetSaslProvider;
import org.jivesoftware.openfire.plugin.ofmeet.sasl.OfMeetSaslServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;

import java.io.File;
import java.io.FilenameFilter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Bundles various Jitsi components into one, standalone Openfire plugin.
 *
 * Changes from earlier version
 * - jitsi-plugin made standard. Extensions moved here:
 * -- Openfire properties to system settings (to configure jitsi)
 * -- autorecord should become jitsi videobridge feature: https://github.com/jitsi/jitsi-videobridge/issues/344
 * - jicofo moved from (modified) jitsiplugin and moved to this class
 */
public class OfMeetPlugin implements Plugin, SessionEventListener, ClusterEventListener, PropertyEventListener
{
    private static final Logger Log = LoggerFactory.getLogger(OfMeetPlugin.class);

    public boolean restartNeeded = false;

    private PluginManager manager;
    public File pluginDirectory;

    private OfMeetIQHandler ofmeetIQHandler;
    private WebAppContext publicWebApp;
    private BookmarkInterceptor bookmarkInterceptor;

    private final JvbPluginWrapper jvbPluginWrapper;
    private final MeetingPlanner meetingPlanner;

    public OfMeetPlugin()
    {
        jvbPluginWrapper = new JvbPluginWrapper();
        meetingPlanner = new MeetingPlanner();
    }

    public String getName()
    {
        return "ofmeet";
    }

    public String getDescription()
    {
        return "OfMeet Plugin";
    }

    public void initializePlugin(final PluginManager manager, final File pluginDirectory)
    {
        this.manager = manager;
        this.pluginDirectory = pluginDirectory;

        // Initialize all Jitsi software, which provided the video-conferencing functionality.
        try
        {
            populateJitsiSystemPropertiesWithJivePropertyValues();
            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize the Jitsi components.", ex );
        }

        // Initialize our own additional functinality providers.
        try
        {
            meetingPlanner.initialize();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to initialize the Meeting Planner.", ex );
        }

        try
        {
            loadPublicWebApp();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while attempting to load the public web application.", ex );
        }

        try
        {
            ClusterManager.addListener(this);

            Log.info("OfMeet Plugin - Initialize email listener");

            checkDownloadFolder(pluginDirectory);

            Log.info("OfMeet Plugin - Initialize IQ handler ");

            ofmeetIQHandler = new OfMeetIQHandler( getVideobridge() );
            XMPPServer.getInstance().getIQRouter().addHandler(ofmeetIQHandler);

            if ( JiveGlobals.getBooleanProperty( "ofmeet.bookmarks.auto-enable", true ) )
            {
                bookmarkInterceptor = new BookmarkInterceptor( this );
                InterceptorManager.getInstance().addInterceptor( bookmarkInterceptor );
            }

            SessionEventDispatcher.addListener(this);

            PropertyEventDispatcher.addListener( this );
        }
        catch (Exception e) {
            Log.error("Could NOT start open fire meetings", e);
        }

        Security.addProvider( new OfMeetSaslProvider() );
        SASLAuthentication.addSupportedMechanism( OfMeetSaslServer.MECHANISM_NAME );
    }

    public void destroyPlugin()
    {
        PropertyEventDispatcher.removeListener( this );

        try
        {
            unloadPublicWebApp();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to unload the public web application of OFMeet.", ex );
        }

        try
        {
            SASLAuthentication.removeSupportedMechanism( OfMeetSaslServer.MECHANISM_NAME );
            Security.removeProvider( OfMeetSaslProvider.NAME );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to remove support for the OfMeet-specific SASL support.", ex );
        }

        try
        {
            meetingPlanner.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Meeting Planner", ex );
        }

        try
        {
            SessionEventDispatcher.removeListener(this);
            XMPPServer.getInstance().getIQRouter().removeHandler(ofmeetIQHandler);
            ofmeetIQHandler = null;
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the OFMeet IQ Handler.", ex );
        }

        try
        {
            jvbPluginWrapper.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Videobridge plugin wrapper.", ex );
        }

        ClusterManager.removeListener(this);

        if ( bookmarkInterceptor != null )
        {
            InterceptorManager.getInstance().removeInterceptor( bookmarkInterceptor );
            bookmarkInterceptor = null;
        }
    }

    protected void loadPublicWebApp() throws Exception
    {
        Log.info( "Initializing public web application" );

        Log.debug( "Identify the name of the web archive file that contains the public web application." );
        final File libs = new File(pluginDirectory.getPath() + File.separator + "lib");
        final File[] matchingFiles = libs.listFiles( new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().startsWith("web-") && name.toLowerCase().endsWith(".war");
            }
        });

        final File webApp;
        switch ( matchingFiles.length )
        {
            case 0:
                Log.error( "Unable to find public web application archive for OFMeet!" );
                return;

            default:
                Log.warn( "Found more than one public web application archive for OFMeet. Using an arbitrary one." );
                // intended fall-through.

            case 1:
                webApp = matchingFiles[0];
                Log.debug( "Using this archive: {}", webApp );
        }

        Log.debug( "Creating new WebAppContext for the public web application." );


        publicWebApp = new WebAppContext();
        publicWebApp.setWar( webApp.getAbsolutePath() );
        publicWebApp.setContextPath( new OFMeetConfig().getWebappContextPath() );

        Log.debug( "Making WebAppContext available on HttpBindManager context." );
        HttpBindManager.getInstance().addJettyHandler( publicWebApp );

// No longer needed? Jitsi Meet now checks if the XMPP server supports anonymous authentication, and will prompt for a login otherwise.
//            if ( JiveGlobals.getBooleanProperty("ofmeet.security.enabled", true ) )
//          {
//              Log.info("OfMeet Plugin - Initialize security");
//              context.setSecurityHandler(basicAuth("ofmeet"));
//          }

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
            final int port = HttpBindManager.getInstance().getHttpBindSecurePort();
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

    /**
     * Jitsi takes most of its configuration through system properties. This method sets these
     * properties, using values defined in JiveGlobals.
     */
    public void populateJitsiSystemPropertiesWithJivePropertyValues()
    {
        System.setProperty( "net.java.sip.communicator.SC_HOME_DIR_LOCATION",  pluginDirectory.getAbsolutePath() );
        System.setProperty( "net.java.sip.communicator.SC_HOME_DIR_NAME",      "." );
        System.setProperty( "net.java.sip.communicator.SC_CACHE_DIR_LOCATION", pluginDirectory.getAbsolutePath() );
        System.setProperty( "net.java.sip.communicator.SC_LOG_DIR_LOCATION",   pluginDirectory.getAbsolutePath() );

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

    private static final SecurityHandler basicAuth(String realm) {

        final OfMeetLoginService loginService = new OfMeetLoginService();
        loginService.setName(realm);

        final Constraint constraint = new Constraint();
        constraint.setName( Constraint.__BASIC_AUTH );
        constraint.setRoles( new String[] { "ofmeet" } );
        constraint.setAuthenticate( true );

        final ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setConstraint( constraint );
        constraintMapping.setPathSpec( "/*" );

        final ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.setAuthenticator( new BasicAuthenticator() );
        securityHandler.setRealmName( realm );
        securityHandler.addConstraintMapping( constraintMapping );
        securityHandler.setLoginService( loginService );

        return securityHandler;
    }

    private void checkDownloadFolder(File pluginDirectory)
    {
        String ofmeetHome = JiveGlobals.getHomeDirectory() + File.separator + "resources" + File.separator + "spank" + File.separator + "ofmeet-cdn";

        try
        {
            File ofmeetFolderPath = new File(ofmeetHome);

            if(!ofmeetFolderPath.exists())
            {
                ofmeetFolderPath.mkdirs();

            }

            List<String> lines = Arrays.asList("Move on, nothing here....");
            Path file = Paths.get(ofmeetHome + File.separator + "index.html");
            Files.write(file, lines, Charset.forName("UTF-8"));

            File downloadHome = new File(ofmeetHome + File.separator + "download");

            if(!downloadHome.exists())
            {
                downloadHome.mkdirs();

            }

            lines = Arrays.asList("Move on, nothing here....");
            file = Paths.get(downloadHome + File.separator + "index.html");
            Files.write(file, lines, Charset.forName("UTF-8"));
        }
        catch (Exception e)
        {
            Log.error("checkDownloadFolder", e);
        }
    }

    //-------------------------------------------------------
    //
    //      clustering
    //
    //-------------------------------------------------------

    @Override
    public void joinedCluster()
    {
        Log.info("OfMeet Plugin - joinedCluster");
        try
        {
            jvbPluginWrapper.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Plugin.", ex );
        }
    }

    @Override
    public void joinedCluster(byte[] arg0)
    {
    }

    @Override
    public void leftCluster()
    {
        Log.info("OfMeet Plugin - leftCluster");
        try
        {
            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to initialize the Jitsi Plugin.", ex );
        }
    }

    @Override
    public void leftCluster(byte[] arg0)
    {
    }

    @Override
    public void markedAsSeniorClusterMember()
    {
        Log.info("OfMeet Plugin - markedAsSeniorClusterMember");
        try
        {
            jvbPluginWrapper.initialize( manager, pluginDirectory );
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to initialize the Jitsi Plugin.", ex );
        }
    }

    public Videobridge getVideobridge()
    {
        return this.jvbPluginWrapper.getVideobridge();
    }

    public void setRecording(String roomName, String path)
    {
        Videobridge videobridge = getVideobridge();

        if (path == null)
        {
            path = JiveGlobals.getHomeDirectory() + File.separator + "resources" + File.separator + "spank" + File.separator + "ofmeet-cdn"  + File.separator + "download";
        }

        for (Conference conference : videobridge.getConferences())
        {
            String room = conference.getName().toString();

            if (room != null && !"".equals(room) && roomName.equals(room))
            {
                for (Content content : conference.getContents())
                {
                    if (content != null && !content.isExpired() && !content.isRecording() && !"data".equals(content.getMediaType().toString()))
                    {
                        Log.info("set videobridge recording " + roomName + " " + content.getMediaType().toString() + " " + path);
                        content.setRecording(true, path);
                        break;
                    }
                }
                break;
            }
        }
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

        boolean skypeAvailable = XMPPServer.getInstance().getPluginManager().getPlugin("ofskype") != null;

        if (OfMeetAzure.skypeids.containsKey(session.getAddress().getNode()))
        {
            String sipuri = OfMeetAzure.skypeids.remove(session.getAddress().getNode());

            IQ iq = new IQ(IQ.Type.set);
            iq.setFrom(session.getAddress());
            iq.setTo(XMPPServer.getInstance().getServerInfo().getXMPPDomain());

            Element child = iq.setChildElement("request", "http://igniterealtime.org/protocol/ofskype");
            child.setText("{'action':'stop_skype_user', 'sipuri':'" + sipuri + "'}");
            XMPPServer.getInstance().getIQRouter().route(iq);

            Log.info("OfMeet Plugin - closing skype session " + sipuri);
        }
    }

    @Override
    public void propertySet( String s, Map<String, Object> map )
    {
        switch (s)
        {
            case OFMeetConfig.OFMEET_WEBAPP_CONTEXTPATH_PROPERTYNAME:
                final String currentValue = this.publicWebApp.getContextPath();
                final String updatedValue = new OFMeetConfig().getWebappContextPath();
                if ( !currentValue.equals( updatedValue ) )
                {
                    Log.debug( "A configuration change requires the web application to be reloaded on a different context path. Old path: {}, new path: {}.", currentValue, updatedValue );
                    try
                    {
                        unloadPublicWebApp();
                        loadPublicWebApp();
                    }
                    catch ( Exception e )
                    {
                        Log.error( "An exception occurred while trying to re-load the web application on a different context path. Old path: {}, new path: {}.", currentValue, updatedValue, e );
                    }
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void propertyDeleted( String s, Map<String, Object> map )
    {
        propertySet( s, map );
    }

    @Override
    public void xmlPropertySet( String s, Map<String, Object> map )
    {

    }

    @Override
    public void xmlPropertyDeleted( String s, Map<String, Object> map )
    {

    }
}
