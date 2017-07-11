<?xml version="1.0" encoding="UTF-8"?>

<!--
  ~ Copyright (c) 2017 Ignite Realtime Foundation. All rights reserved.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~      http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<%@ page import="org.jivesoftware.util.*,
                 java.util.*,
                 org.freeswitch.esl.client.transport.message.EslMessage,
                 java.net.URLEncoder"                 
    errorPage="error.jsp"
%>
<%@ page import="org.xmpp.packet.JID" %>
<%@ page import="org.jivesoftware.openfire.plugin.ofswitch.OfSwitchPlugin" %>

<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="c"%>
<%@ taglib uri="http://java.sun.com/jstl/fmt_rt" prefix="fmt" %>
<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager"  />
<% webManager.init(request, response, session, application, out ); %>

<html>
<head>
<title>Freeswitch Users</title>
<meta name="pageID" content="freeswitch-users"/>
</head>
<body>
<div class="jive-table">
<table cellpadding="0" cellspacing="0" border="0" width="100%">
<%
	if (OfSwitchPlugin.self != null)
	{	
		EslMessage resp = OfSwitchPlugin.self.sendFWCommand("list_users");
		
		if (resp != null)
		{
			List<String> bodyLines = resp.getBodyLines();
			int count = 0;

			for (String line : bodyLines) 
			{
				if (line.startsWith("+OK") == false)
				{
					String[] columns = line.split("\\|");

					%><tr class="jive-<%= (((count%2)==0) ? "even" : "odd") %>"><%

					for (int i=0; i<columns.length; i++)
					{
						String tagName = (count == 0 ? "th" : "td");
						String tagValue = (count == 0 ? columns[i].substring(0, 1).toUpperCase() + columns[i].substring(1) : columns[i]);

						%><<%= tagName %>><%= tagValue %></<%= tagName %>><%
					}

					%></tr><%			
					count++;
				}
			}
		} else {
			
			if (JiveGlobals.getBooleanProperty("freeswitch.enabled", true))
			{
				%>Please wait.......<%
			} else {
				%>Disabled<%			
			}
		}
	}
%>
</table>
</div>
</body>
</html>
