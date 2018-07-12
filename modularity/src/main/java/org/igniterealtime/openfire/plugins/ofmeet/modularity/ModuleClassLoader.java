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

package org.igniterealtime.openfire.plugins.ofmeet.modularity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * A classloader for an Openfire plugin module. This implementation is similar
 * to the {@link org.jivesoftware.openfire.container.PluginClassLoader}, but
 * delegates to a parent that's explicitly defined.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class ModuleClassLoader extends URLClassLoader
{
    private static final Logger Log = LoggerFactory.getLogger( ModuleClassLoader.class );

    private List<JarURLConnection> cachedJarFiles = new ArrayList<>();

    public ModuleClassLoader( ClassLoader parent )
    {
        super( new URL[]{}, parent );
    }

    public void addDirectory( final File directory, final boolean developmentMode )
    {
        Log.debug( "Adding module directory: {}.", directory);
        File[] jars = directory.listFiles( ( dir, name ) -> name.endsWith( ".jar" ) || name.endsWith( ".zip" ) );
        if ( jars != null )
        {
            for ( final File jar : jars )
            {
                try
                {
                    if ( jar != null && jar.isFile() )
                    {
                        String jarFileUri = jar.toURI().toString() + "!/";
                        if ( developmentMode )
                        {
                            // Do not add plugin-pluginName.jar to classpath.
                            if ( !jar.getName().equals( "plugin-" + directory.getName() + ".jar" ) )
                            {
                                addURLFile( new URL( "jar", "", -1, jarFileUri ) );
                            }
                        }
                        else
                        {
                            addURLFile( new URL( "jar", "", -1, jarFileUri ) );
                        }
                    }
                }
                catch ( Exception e )
                {
                    Log.error( "Unable to add file '{}'.", jar, e );
                }
            }
        }
    }

    /**
     * Add the given URL to the classpath for this class loader,
     * caching the JAR file connection so it can be unloaded later
     *
     * @param file URL for the JAR file or directory to append to classpath
     */
    public void addURLFile( URL file )
    {
        Log.debug( "Adding module file: {}.", file );
        try
        {
            // open and cache JAR file connection
            URLConnection uc = file.openConnection();
            if ( uc instanceof JarURLConnection )
            {
                uc.setUseCaches( true );
                ((JarURLConnection) uc).getManifest();
                cachedJarFiles.add( (JarURLConnection) uc );
            }
        }
        catch ( Exception e )
        {
            Log.warn( "Failed to cache module JAR file: " + file.toExternalForm() );
        }
        addURL( file );
    }

    /**
     * Unload any JAR files that have been cached by this plugin
     */
    public void unloadJarFiles()
    {
        for ( JarURLConnection url : cachedJarFiles )
        {
            try
            {
                Log.debug( "Unloading module JAR file {}.", url.getJarFile().getName() );
                url.getJarFile().close();
            }
            catch ( Exception e )
            {
                Log.error( "Failed to unload module JAR file {}", url, e );
            }
        }
    }
}
