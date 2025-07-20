package org.jivesoftware.openfire.plugin.ofmeet;

import org.dom4j.Element;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.*;


/**
 * custom IQ handler for user and group properties JSON request/response.
 */
public class OfMeetIQHandler extends IQHandler implements ServerFeaturesProvider
{
    private final static Logger Log = LoggerFactory.getLogger( OfMeetIQHandler.class );

    public OfMeetIQHandler( )
    {
        super("Openfire Meetings IQ Handler");
    }

    @Override
    public IQ handleIQ(IQ iq)
    {
        IQ reply = IQ.createResultIQ(iq);

        try {
            Log.info("Openfire Meetings handleIQ \n" + iq.toString());
            final Element element = iq.getChildElement();

			if (element.getQName().getName().equals("json")) {
				JSONObject requestJSON = new JSONObject( element.getText());
				String action = requestJSON.getString("action");

				if ("get_user_properties".equals(action)) getUserProperties(iq.getFrom().getNode(), reply, requestJSON);
				if ("set_user_properties".equals(action)) setUserProperties(iq.getFrom().getNode(), reply, requestJSON);
				if ("get_user_groups".equals(action)) getUserGroups(iq.getFrom().getNode(), reply, requestJSON);
				if ("get_group".equals(action)) getGroup(iq.getFrom().getNode(), reply, requestJSON);
				if ("get_conference_id".equals(action)) getConferenceId(iq.getFrom().getNode(), reply, requestJSON);
			}
			else
			
			if (element.getQName().getName().equals("query")) {
				String type = element.attribute("type").getText();
				
				if ("jitsi".equals(type)) {
					String webUrl = OfMeetPlugin.self.getWebappURL().toString();
					
					if (element.attribute("id") != null) {
						webUrl = webUrl + "/" + element.attribute("id").getText();
					}

					Element childElement = reply.setChildElement("query", "urn:xmpp:http:online-meetings:0");					
					Element initiate = childElement.addElement("initiate");
					initiate.addAttribute("type", type);
					Element url = initiate.addElement("url");
					url.setText(webUrl);			
					
					Element invite  = childElement.addElement("invite", "urn:xmpp:call-invites:0");	
					Element external = invite.addElement("external");	
					external.addAttribute("uri", webUrl);	
				} else {
					reply.setError(new PacketError(PacketError.Condition.internal_server_error, PacketError.Type.modify, "online meeting type not supported : " + type ));
				}					
			}				
			
        } catch(Exception e) {
            Log.error("Openfire Meetings handleIQ", e);
            reply.setError(new PacketError(PacketError.Condition.internal_server_error, PacketError.Type.modify, e.toString()));
        }
		
        return reply;		
    }

    @Override
    public IQHandlerInfo getInfo()
    {
        return new IQHandlerInfo("query", "urn:xmpp:http:online-meetings:0");
    }
	
    @Override
    public Iterator<String> getFeatures()
    {
        final ArrayList<String> features = new ArrayList<>();
        features.add( "urn:xmpp:http:online-meetings:initiate:0" );
		features.add( "urn:xmpp:http:online-meetings#jitsi" );
        return features.iterator();
    }	

    private void getConferenceId(String defaultUsername, IQ reply, JSONObject requestJSON)
    {
        Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

        try {
            String roomName = requestJSON.getString("room");


        } catch (Exception e1) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
            return;
        }
    }

    private void setUserProperties(String username, IQ reply, JSONObject requestJSON)
    {
        Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

        try {
            UserManager userManager = XMPPServer.getInstance().getUserManager();
            User user = userManager.getUser(username);

            if (requestJSON != null)
            {
                Iterator<?> keys = requestJSON.keys();

                while( keys.hasNext() )
                {
                    String key = (String)keys.next();
                    String value = requestJSON.getString(key);

                    user.getProperties().put(key, value);
                }
            }

        } catch (Exception e) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User " + username + " " + requestJSON.toString() + " " + e));
            return;
        }
    }

    private void getUserProperties(String defaultUsername, IQ reply, JSONObject requestJSON)
    {
        Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

        try {
            String username = requestJSON.getString("username");

            if (username == null) username = defaultUsername;

            UserManager userManager = XMPPServer.getInstance().getUserManager();
            User user = userManager.getUser(username);

            JSONObject userJSON = new JSONObject();

            userJSON.put("username", JID.unescapeNode(user.getUsername()));
            userJSON.put("name", user.isNameVisible() ? removeNull(user.getName()) : "");
            userJSON.put("email", user.isEmailVisible() ? removeNull(user.getEmail()) : "");

            for(Map.Entry<String, String> props : user.getProperties().entrySet())
            {
                userJSON.put(props.getKey(), props.getValue());
            }

            childElement.setText(userJSON.toString());

        } catch (UserNotFoundException e) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User not found"));
            return;

        } catch (Exception e1) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
            return;
        }
    }

    private void getUserGroups(String defaultUsername, IQ reply, JSONObject requestJSON)
    {
        Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

        try {
            String username = requestJSON.getString("username");

            if (username == null) username = defaultUsername;

            UserManager userManager = XMPPServer.getInstance().getUserManager();
            User user = userManager.getUser(username);

            Collection<Group> groups = GroupManager.getInstance().getGroups(user);
            JSONArray groupsJSON = new JSONArray();
            int index = 0;

            for (Group group : groups)
            {
                groupsJSON.put(index++, getJsonFromGroupXml(group.getName()));
            }

            childElement.setText(groupsJSON.toString());

        } catch (UserNotFoundException e) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, "User not found"));
            return;

        } catch (Exception e1) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
            return;
        }
    }

    private void getGroup(String defaultUsername, IQ reply, JSONObject requestJSON)
    {
        Element childElement = reply.setChildElement("response", "http://igniterealtime.org/protocol/ofmeet");

        try {
            JSONObject groupJSON = getJsonFromGroupXml(requestJSON.getString("groupname"));
            childElement.setText(groupJSON.toString());

        } catch (Exception e1) {
            reply.setError(new PacketError(PacketError.Condition.not_allowed, PacketError.Type.modify, requestJSON.toString() + " " + e1));
            return;
        }
    }

    private JSONObject getJsonFromGroupXml(String groupname)
    {
        JSONObject groupJSON = new JSONObject();

        try {
            Group group = GroupManager.getInstance().getGroup(groupname);

            boolean isSharedGroup = RosterManager.isSharedGroup(group);
            Map<String, String> properties = group.getProperties();
            String showInRoster = (isSharedGroup ? properties.get("sharedRoster.showInRoster") : "");

            groupJSON.put("name", group.getName());
            groupJSON.put("desc", group.getDescription());
            groupJSON.put("count", group.getMembers().size() + group.getAdmins().size());
            groupJSON.put("shared", String.valueOf(isSharedGroup));
            groupJSON.put("display", (isSharedGroup ? properties.get("sharedRoster.displayName") : ""));
            groupJSON.put("specified_groups", String.valueOf("onlyGroup".equals(showInRoster) && properties.get("sharedRoster.groupList").trim().length() > 0));
            groupJSON.put("visibility", showInRoster);
            groupJSON.put("groups", (isSharedGroup ? properties.get("sharedRoster.groupList") : ""));

            for(Map.Entry<String, String> props : properties.entrySet())
            {
                groupJSON.put(props.getKey(), props.getValue());
            }

            JSONArray membersJSON = new JSONArray();
            JSONArray adminsJSON = new JSONArray();
            int i = 0;

            for (JID memberJID : group.getMembers())
            {
                JSONObject memberJSON = new JSONObject();
                memberJSON.put("jid", memberJID.toString());
                memberJSON.put("name", memberJID.getNode());
                membersJSON.put(i++, memberJSON);
            }

            groupJSON.put("members", membersJSON);
            i = 0;

            for (JID memberJID : group.getAdmins())
            {
                JSONObject adminJSON = new JSONObject();
                adminJSON.put("jid", memberJID.toString());
                adminJSON.put("name", memberJID.getNode());
                adminsJSON.put(i++, adminJSON);
            }
            groupJSON.put("admins", adminsJSON);

        } catch (Exception e) {
            Log.error("getJsonFromGroupXml", e);
        }

        return groupJSON;
    }

    private String removeNull(String s)
    {
        if (s == null)
        {
            return "";
        }

        return s.trim();
    }
}
