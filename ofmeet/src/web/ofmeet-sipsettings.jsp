<%--
  ~ Copyright (C) 2018 Ignite Realtime Foundation. All rights reserved.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  --%>
<%@ page import="org.jivesoftware.openfire.XMPPServer" %>
<%@ page import="org.jivesoftware.openfire.plugin.ofmeet.OfMeetPlugin" %>
<%@ page import="org.jivesoftware.util.CookieUtils" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ page import="org.jivesoftware.util.StringUtils" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.Map" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="admin" prefix="admin" %>
<jsp:useBean id="ofmeetConfig" class="org.igniterealtime.openfire.plugin.ofmeet.config.OFMeetConfig"/>
<%
    boolean update = request.getParameter("update") != null;

	final Cookie csrfCookie = CookieUtils.getCookie( request, "csrf" );
	final String csrfParam = ParamUtils.getParameter( request, "csrf" );

	// Get handle on the plugin
	final OfMeetPlugin container = (OfMeetPlugin) XMPPServer.getInstance().getPluginManager().getPlugin("ofmeet");

	final Map<String, String> errors = new HashMap<>();

    if ( update )
	{
		if ( csrfCookie == null || csrfParam == null || !csrfCookie.getValue().equals( csrfParam ) )
		{
			errors.put( "csrf", "CSRF Failure!" );
		}

        final String jigasiServerAddress = request.getParameter( "jigasiServerAddress" );
        final String jigasiDomainBase = request.getParameter( "jigasiDomainBase" );
        final String jigasiPassword = request.getParameter( "jigasiPassword" );
        final String jigasiUserId = request.getParameter( "jigasiUserId" );

        if ( errors.isEmpty() )
		{
		    ofmeetConfig.jigasiServerAddress.set( jigasiServerAddress );
		    ofmeetConfig.jigasiDomainBase.set( jigasiDomainBase );
		    ofmeetConfig.jigasiPassword.set( jigasiPassword );
		    ofmeetConfig.jigasiUserId.set( jigasiUserId );

		    // Only reload everything if something changed.
		    if ( ofmeetConfig.jigasiServerAddress.wasChanged()
              || ofmeetConfig.jigasiDomainBase.wasChanged()
              || ofmeetConfig.jigasiPassword.wasChanged()
              || ofmeetConfig.jigasiUserId.wasChanged() )
            {
                container.populateJitsiSystemPropertiesWithJivePropertyValues();
            }
            response.sendRedirect( "ofmeet-sipsettings.jsp?settingsSaved=true" );
            return;
		}
	}

    final String csrf = StringUtils.randomString( 15 );
	CookieUtils.setCookie( request, response, "csrf", csrf, -1 );

	pageContext.setAttribute( "csrf", csrf );
	pageContext.setAttribute( "errors", errors );
%>
<html>
<head>
	<title><fmt:message key="sipsettings.title" /></title>
	<meta name="pageID" content="ofmeet-sipsettings"/>
</head>
<body>

<c:choose>
	<c:when test="${not empty param.settingsSaved and empty errors}">
		<admin:infoBox type="success"><fmt:message key="config.page.configuration.save.success" /></admin:infoBox>
	</c:when>
	<c:otherwise>
		<c:forEach var="err" items="${errors}">
			<admin:infobox type="error">
				<c:choose>
					<c:when test="${err.key eq 'csrf'}"><fmt:message key="global.csrf.failed"/></c:when>
					<c:otherwise>
						<c:if test="${not empty err.value}">
							<c:out value="${err.value}"/>
						</c:if>
						(<c:out value="${err.key}"/>)
					</c:otherwise>
				</c:choose>
			</admin:infobox>
		</c:forEach>
	</c:otherwise>
</c:choose>

<p><fmt:message key="sipsettings.introduction" /></p>

<form action="ofmeet-sipsettings.jsp" method="post">

    <fmt:message key="sipsettings.account.title" var="boxtitleAccount"/>
    <admin:contentBox title="${boxtitleAccount}">
        <p>
            <fmt:message key="sipsettings.account.description"/>
        </p>
        <table cellpadding="3" cellspacing="0" border="0" width="100%">
            <tr>
                <td width="200"><label for="jigasiUserId"><fmt:message key="sipsettings.account.user-id"/>:</label></td>
                <td><input type="text" size="60" maxlength="100" name="jigasiUserId" id="jigasiUserId" value="${ofmeetConfig.jigasiUserId.get() == null ? '' : ofmeetConfig.jigasiUserId.get()}"></td>
            </tr>
            <tr>
                <td width="200"><label for="jigasiPassword"><fmt:message key="sipsettings.account.password"/>:</label></td>
                <td><input type="password" size="60" maxlength="100" name="jigasiPassword" id="jigasiPassword" value="${ofmeetConfig.jigasiPassword.get() == null ? '' : ofmeetConfig.jigasiPassword.get()}"></td>
            </tr>
            <tr>
                <td width="200"><label for="jigasiServerAddress"><fmt:message key="sipsettings.account.server-address"/>:</label></td>
                <td><input type="text" size="60" maxlength="100" name="jigasiServerAddress" id="jigasiServerAddress" value="${ofmeetConfig.jigasiServerAddress.get() == null ? '' : ofmeetConfig.jigasiServerAddress.get()}"></td>
            </tr>
            <tr>
                <td width="200"><label for="jigasiDomainBase"><fmt:message key="sipsettings.account.domain-base"/>:</label></td>
                <td><input type="text" size="60" maxlength="100" name="jigasiDomainBase" id="jigasiDomainBase" value="${ofmeetConfig.jigasiDomainBase.get() == null ? '' : ofmeetConfig.jigasiDomainBase.get()}"></td>
            </tr>
        </table>
    </admin:contentBox>

    <input type="hidden" name="csrf" value="${csrf}">

    <input type="submit" name="update" value="<fmt:message key="global.save_settings" />">

</form>
</body>
</html>
