call mvn clean package

cd target
rename ofmeet-openfire-plugin-assembly.jar ofmeet.jar
rd "D:\Openfire\openfire_5_0_0\plugins\ofmeet" /q /s
del "D:\Openfire\openfire_5_0_0\plugins\ofmeet.jar" 
del /q "D:\Openfire\openfire_5_0_0\logs\*.*"
copy ofmeet.jar D:\Openfire\openfire_5_0_0\plugins\ofmeet.jar

pause