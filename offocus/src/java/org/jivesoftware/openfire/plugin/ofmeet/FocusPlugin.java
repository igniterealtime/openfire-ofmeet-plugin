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

import org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig;
import org.jitsi.jicofo.FocusManager;
import org.jitsi.jicofo.auth.AuthenticationAuthority;
import org.jitsi.jicofo.reservation.ReservationSystem;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.io.File;
import java.util.TimerTask;

/**
 * An Openfire plugin that provides 'focus' functionality to Openfire.
 *
 * This plugin is largely based on Jitsi's Jicofo implementation, which is complemented by Openfire-specific provisioning.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 */
public class FocusPlugin implements Plugin
{
    private static final Logger Log = LoggerFactory.getLogger( FocusPlugin.class );

    private final JitsiJicofoWrapper jitsiJicofoWrapper = new JitsiJicofoWrapper();

    @Override
    public void initializePlugin( PluginManager pluginManager, File file )
    {
        ensureFocusUser();

        // OFMeet must be fully loaded before focus starts to do service discovery.
        TaskEngine.getInstance().schedule( new TimerTask()
        {
            @Override
            public void run()
            {
                if ( pluginManager.getPlugin( "ofmeet" ) != null )
                {
                    try
                    {
                        jitsiJicofoWrapper.initialize();
                    }
                    catch ( Exception e )
                    {
                        Log.error( "An exception occurred while initializing the Jitsi Jicofo wrapper.", e );
                    }
                    TaskEngine.getInstance().cancelScheduledTask( this );
                }
                Log.trace( "Waiting for ofmeet plugin to become available..." );
            }
        }, 0, 500 );
    }

    private void ensureFocusUser()
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
    public void destroyPlugin()
    {
        try
        {
            jitsiJicofoWrapper.destroy();
        }
        catch ( Exception ex )
        {
            Log.error( "An exception occurred while trying to destroy the Jitsi Jicofo wrapper.", ex );
        }
    }

    public ReservationSystem getReservationService()
    {
        return this.jitsiJicofoWrapper.getReservationService();
    }

    public FocusManager getFocusManager()
    {
        return this.jitsiJicofoWrapper.getFocusManager();
    }

    public AuthenticationAuthority getAuthenticationAuthority()
    {
        return this.jitsiJicofoWrapper.getAuthenticationAuthority();
    }
}
