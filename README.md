# Steam Deploy

#### Maven plugin to automate deployment of steam apps

## Reporitories
```xml
<pluginRepositories>
	<pluginRepository>
		<id>nexus.kbra.lu-releases</id>
		<url>https://nexus.kbra.lu/repository/maven-releases/</url>
	</pluginRepository>
	<pluginRepository>
		<id>nexus.kbra.lu-snapshots</id>
		<url>https://nexus.kbra.lu/repository/maven-snapshots/</url>
	</pluginRepository>
</pluginRepositories>
```

## Plugin configuration
```xml
<properties>
  <steam.appId>123456</steam.appId>
  <steam.appName>${project.name}<steam.appName>
  <steam.branch>default</steam.branch>
  <steam.depotId.windows>123456</steam.depotId.windows>
  <steam.depotId.linux>123456</steam.depotId.linux>
  <steam.user>steam</steam.user> <!-- leave empty to use the current user or use the username that has the steamcmd configuration installed -->
</properties>
<build>
  <plugins>
    <plugin>
		<groupId>lu.kbra</groupId>
		<artifactId>steam-deploy</artifactId>
		<version>1.1.6</version>
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
			<user>${steam.user}</user>
			<steamcmdPath>/usr/games/steamcmd</steamcmdPath>
			<serverId>steam</serverId> <!-- optional, not needed if username/password are provided -->
			<username></username> <!-- optional, derived from the server config ~/.m2/settings.xml with the serverId -->
			<password></password> <!-- same -->
			<buildScript>steam/app_build_1234.vdf</buildScript>

			<filterVdfs>true|false</filterVdfs>
			<filters>
				<appId>${steam.appId}</appId>
				<appName>${steam.appName}</appName>
				<description>${project.version}</description>
				<branch>${steam.branch}</branch>
				<contentRoot>${project.build.directory}/dist/</contentRoot>
				<buildOutput>${project.build.directory}/steam-output</buildOutput>
				<depotId.windows>${steam.depotId.windows}</depotId.windows>
				<depotId.linux>${steam.depotId.linux}</depotId.linux>
			</filters>
		</configuration>
	</plugin>
  </plugins>
</build>
```
I recommend putting the plugin configuration inside a profile, then triggering the profile only when needed, f.ex: `mvn -Psteam-deploy deploy` or only triggering the deployment plugin: `mvn lu.kbra:steam-deploy:deploy`.
Leave `steam.user` empty to use the current user to execute steamcmd. I recomment leaving it empty and only overwriting it when needed using `-Dsteam.user=...`, f.ex. inside your CI script.

## Example VDFs:
### App Build
```vdf
"AppBuild"
{
	"AppID" "@appId@"
	"Desc" "@description@"
	"Preview" "0"
	"SetLive" "@branch@"
	"ContentRoot" "@contentRoot@"
	"BuildOutput" "@buildOutput@"
	"verbose" "0"
	"Depots"
	{
		"@depotId.linux@" "depot_build_linux_generic.vdf"
		"@depotId.windows@" "depot_build_windows_generic.vdf"
	}
}
```
### Depots
```vdf
"DepotBuild"
{
	"DepotID" "@depotId.linux@"

	"FileMapping"
	{
		"LocalPath" "linux/@appName@/*"
		"DepotPath" "linux/"
		"Recursive" "1"
  }
}

"DepotBuild"
{
	"DepotID" "@depotId.windows@"

	"FileMapping"
	{
		"LocalPath" "windows/@appName@/*"
		"DepotPath" "windows/"
		"Recursive" "1"
  }
}

```
