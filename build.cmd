call "C:\Apache Software Foundation\apache-maven-3.5.0\bin\mvn" clean package

rd "C:\openfire_4_1_5\plugins\ofmeet" /q /s
rd "C:\openfire_4_1_5\plugins\offocus" /q /s
rd "C:\openfire_4_1_5\plugins\ofswitch" /q /s

del "C:\openfire_4_1_5\plugins\ofmeet.jar" 
del "C:\openfire_4_1_5\plugins\offocus.jar" 
del "C:\openfire_4_1_5\plugins\ofswitch.jar" 

copy C:\Projects\ignite\ofmeet-openfire-plugin-dele\ofmeet\target\ofmeet.jar "C:\openfire_4_1_5\plugins"
copy C:\Projects\ignite\ofmeet-openfire-plugin-dele\offocus\target\offocus.jar "C:\openfire_4_1_5\plugins"
copy C:\Projects\ignite\ofmeet-openfire-plugin-dele\ofswitch\target\ofswitch.jar "C:\openfire_4_1_5\plugins"

del "C:\openfire_4_1_5\logs\*.*"
pause