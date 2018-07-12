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

package org.jivesoftware.openfire.plugin.ofmeet;

import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.WebAppContext;
import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.plugin.ofmeet.jetty.OfMeetLoginService;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

public class WebappWrapper implements PropertyEventListener
{
    private static Logger Log = LoggerFactory.getLogger( WebappWrapper.class );

    private WebAppContext publicWebApp;
    private PluginManager manager;
    private File pluginDirectory;

    public WebappWrapper( final PluginManager manager, final File pluginDirectory )
    {
        this.manager = manager;
        this.pluginDirectory = pluginDirectory;
    }

    protected void initialize()
    {
        Log.info( "Initializing public web application" );

        Log.debug( "Identify the name of the web archive file that contains the public web application." );
        final File libs = new File( pluginDirectory.getPath() + File.separator + "lib");
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

    public void destroy()
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

    private static final SecurityHandler basicAuth( String realm) {

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
                        destroy();
                        initialize();
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
