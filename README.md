s](https://github.com/igniterealtime/openfire-ofmeet-plugin/workflows/Java%20CI/badge.svg)](https://github.com/igniterealtime/openfire-ofmeet-plugin/actions)

Openfire Meetings project
=========================

This project produces a plugin that provides an online meeting solution for Openfire using Jitsi Meet.

It implements [XEP-0483](https://xmpp.org/extensions/xep-0483.html) which defines an approach to request initiation of an online meeting via an HTTP server and receive a URL can be used to join and invite others to the meeting.

The OFMeet project bundles various third-party products, notably:
- the [Jitsi Videobridge](https://github.com/jitsi/jitsi-videobridge) project;
- the [Jitsi Conference Focus (jicofo)](https://github.com/jitsi/jicofo) project; 
- the [Jitsi Meet](https://github.com/jitsi/jitsi-meet) webclient.

Installation
------------
Install the ofmeet plugins into your Openfire instance.

Build instructions
------------------

This project is a Apache Maven project, and is build using the standard Maven invocation:

    mvn clean package

After a successful execution, the plugin should be available in this location:

    ofmeet/target/ofmeet.jar
