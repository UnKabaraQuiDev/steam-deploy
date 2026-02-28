# Steam Deploy

#### Maven plugin to automate deployment of steam apps

```xml
<repositories>
  <repository>
    <id>nexus.kbra.lu-releases</id>
    <url>https://nexus.kbra.lu/repository/maven-releases/</url>
  </repository>
  <repository>
    <id>nexus.kbra.lu-snapshots</id>
    <url>https://nexus.kbra.lu/repository/maven-snapshots/</url>
  </repository>
</repositories>
```

```xml
<properties>
  <steam.appId>123456</steam.appId>
  <steam.buildScript>${project.basedir}/../steam/app_build_${steam.appId}.vdf</steam.buildScript>
</properties>
<build>
  <plugins>
    <plugin>
		<groupId>lu.kbra</groupId>
		<artifactId>steam-deploy</artifactId>
		<version>1.0</version>
		<executions>
			<execution>
				<id>steam-deploy</id>
				<phase>deploy</phase>
				<goals>
					<goal>deploy</goal>
				</goals>
			</execution>
		</executions>
		<configuration>
			<steamcmdPath>steamcmd</steamcmdPath>
			<serverId>steam</serverId> <!-- optional, not needed if username/password are provided -->
			<username></username> <!-- optional, derived from the server config ~/.m2/settings.xml with the serverId -->
			<password></password> <!-- same -->
			<buildScript>${steam.buildScript}</buildScript>
		</configuration>
	</plugin>
  </plugins>
</build>
```
