/**
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

package org.jivesoftware.openfire.plugin.ofswitch;

import org.apache.log4j.Logger;

import org.xmpp.component.Component;
import org.xmpp.component.AbstractComponent;
import org.xmpp.component.ComponentException;
import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;

import org.jivesoftware.openfire.XMPPServer;

import org.xmpp.packet.*;
import java.util.*;
import java.util.concurrent.*;
import org.dom4j.*;


public class CallControlComponent extends AbstractComponent
{
    protected Logger Log = Logger.getLogger(getClass().getName());

	//-------------------------------------------------------
	//
	//
	//
	//-------------------------------------------------------

	public void componentEnable()
	{
		Log.info("CallControlComponent enabled");
	}

	public void componentDestroyed()
	{
		try {
			Log.info("CallControlComponent disabled");
		}
		catch(Exception e) { }
	}

	//-------------------------------------------------------
	//
	//
	//
	//-------------------------------------------------------

	@Override public String getDescription()
	{
		return "CallControl Component";
	}


	@Override public String getName()
	{
		return "callcontrol";
	}

	@Override public String getDomain()
	{
		return  XMPPServer.getInstance().getServerInfo().getXMPPDomain();
	}

	@Override public void postComponentStart()
	{

	}

	@Override public void postComponentShutdown()
	{

	}

	public JID getComponentJID()
	{
		return new JID(getName() + "." + getDomain());
	}

	//-------------------------------------------------------
	//
	//
	//
	//-------------------------------------------------------


    @Override protected void handleMessage(Message received)
    {
		Log.info("handleMessage \n"+ received.toString());
    }

    @Override protected void handlePresence(Presence received)
    {
		Log.info("handlePresence \n"+ received.toString());
    }

	@Override protected void handleIQResult(IQ iq)
	{
		Log.info("handleIQResult \n"+ iq.toString());
	}

	@Override protected void handleIQError(IQ iq)
	{
		Log.info("handleIQError \n"+ iq.toString());
	}

   @Override public IQ handleDiscoInfo(IQ iq)
    {
		IQ iq1 = IQ.createResultIQ(iq);
		iq1.setType(org.xmpp.packet.IQ.Type.result);
		iq1.setChildElement(iq.getChildElement().createCopy());

		Element queryElement = iq1.getChildElement();
		Element identity = queryElement.addElement("identity");

		identity.addAttribute("category", "component");
		identity.addAttribute("name", "callcontrol");

		queryElement.addElement("feature").addAttribute("var", "http://jitsi.org/protocol/jigasi");
		queryElement.addElement("feature").addAttribute("var",  "urn:xmpp:rayo:0");

		Log.debug("handleDiscoInfo \n"+ iq1.toString());
		return iq1;
    }


   @Override public IQ handleDiscoItems(IQ iq)
    {
		IQ iq1 = IQ.createResultIQ(iq);
		iq1.setType(org.xmpp.packet.IQ.Type.result);
		iq1.setChildElement(iq.getChildElement().createCopy());

		Log.debug("handleDiscoItems \n"+ iq1.toString());
		return iq1;
    }

   @Override public IQ handleIQGet(IQ iq)
    {
		return handleIQPacket(iq);
	}

   @Override public IQ handleIQSet(IQ iq)
    {
		return handleIQPacket(iq);
	}

   private IQ handleIQPacket(final IQ iq)
    {
		IQ reply = IQ.createResultIQ(iq);

		try
		{
			Log.debug("handleIQPacket\n" + iq);

			Element element = iq.getChildElement();
			String namespace = element.getNamespaceURI();
			String request = element.getName();

			String confJid = null;

			if ("dial".equals(request) && "urn:xmpp:rayo:1".equals(namespace))
			{
				String from = element.attributeValue("from");
				String to = element.attributeValue("to");

				for ( Iterator i = element.elementIterator( "header" ); i.hasNext(); )
				{
					Element header = (Element) i.next();
					String name = header.attributeValue("name");
					String value = header.attributeValue("value");

					if ("JvbRoomName".equals(name)) confJid = value;
				}

				if (confJid != null)
				{
					final String callId = Long.toHexString(System.currentTimeMillis());
					final String roomName = (new JID(confJid)).getNode();

					Log.info("Got dial request " + confJid + " -> " + to + " callId " + callId);

					OfSwitchPlugin.self.makeCall(roomName, to);

					String callResource = "xmpp:" + callId + "@" + getJID();

					final Element childElement = reply.setChildElement("ref", "urn:xmpp:rayo:1");
					childElement.addAttribute("uri", (String) "xmpp:" + callId + "@" + getJID());
					childElement.addAttribute("id", (String)  callId);
				}
			}
		}
		catch (Exception e)
		{
			Log.error("handleIQPacket", e);
			reply.setError(PacketError.Condition.internal_server_error);
		}

		return reply;
	}

	private void sendPacket(Packet packet)
	{
		try {
			ComponentManagerFactory.getComponentManager().sendPacket(this, packet);
		} catch (Exception e) {

			Log.error("CallControlComponent sendPacket ", e);
		}
	}

}
