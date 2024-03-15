[![Build Status](https://travis-ci.org/igniterealtime/Openfire-Meetings.svg?branch=master)](https://travis-ci.org/igniterealtime/Openfire-Meetings)

Openfire Meetings project
=========================

This project produces two Openfire plugins, offocus and ofmeet, that, combined, provide a WebRTC-based video conference soluhttps://github.com/igniterealtime/openfire-ofmeet-plugin/blob/master/README.mdtion for Openfire.

The OFMeet project bundles various third-party products, notably:
- the [Jitsi Videobridge](https://github.com/jitsi/jitsi-videobridge) project;
- the [Jitsi Conference Focus (jicofo)](https://github.com/jitsi/jicofo) project; 
- the [Jitsi Meet](https://github.com/jitsi/jitsi-meet) webclient.

Installation
------------
Install the offocus and ofmeet plugins into your Openfire instance.

Build instructions
------------------

This project is a Apache Maven project, and is build using the standard Maven invocation:

    mvn clean package

After a successful execution, the two plugins should be available in these locations:

    offocus/target/offocus.jar
    ofmeet/target/ofmeet.jar
