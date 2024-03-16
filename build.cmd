call mvn clean package

cd target
rename ofmeet-openfire-plugin-assembly.jar ofmeet.jar
rd "D:\Openfire\openfire_4_8_1\plugins\ofmeet" /q /s
del "D:\Openfire\openfire_4_8_1\plugins\ofmeet.jar" 
del /q "D:\Openfire\openfire_4_8_1\logs\*.*"
copy ofmeet.jar D:\Openfire\openfire_4_8_1\plugins\ofmeet.jar

pause