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

import org.jivesoftware.openfire.container.PluginManager;

import javax.servlet.GenericServlet;
import java.io.File;
import java.util.Map;

/**
 * A plugin module is a part of an Openfire plugin. loaded by a class loader
 * that's distinct from the class loader that loads the plugin itself, or any
 * sibling modules.
 *
 * A module is distinctly different from a plugin in a parent/child relationship
 * with another plugin, as those use the same class loader. Instead, the module
 * framework allows one to use multiple, distinct classloaders within one
 * plugin. This facilitates having multiple, possibly conflicting, versions of
 * the same dependency library used in a plugin.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public interface Module
{
    void initialize( final PluginManager manager, final File pluginDirectory );

    void destroy();

    void reloadConfiguration();

    Map<String, String> getServlets();
}
