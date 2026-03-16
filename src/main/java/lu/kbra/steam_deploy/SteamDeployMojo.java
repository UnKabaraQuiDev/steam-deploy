package lu.kbra.steam_deploy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true)
public class SteamDeployMojo extends AbstractMojo {

	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("@([A-Za-z0-9_.-]+)@");

	@Parameter(property = "steamcmdPath", required = true)
	private String steamcmdPath;

	@Parameter(property = "username")
	private String username;

	@Parameter(property = "password")
	private String password;

	@Parameter(property = "steam.guard")
	private String guardCode;

	@Parameter(property = "buildScript", required = true)
	private File buildScript;

	@Parameter(property = "serverId", defaultValue = "steam")
	private String serverId;

	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings settings;

	@Parameter(defaultValue = "${project.build.directory}/steam-deploy/", readonly = true, required = true)
	private File buildDirectory;

	@Parameter(defaultValue = "false")
	private boolean usingUser = false;

	@Parameter
	private String user;

	@Parameter(defaultValue = "false")
	private boolean filterVdfs = true;

	@Parameter
	private Map<String, String> filters = new HashMap<>();

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject session;

	@Override
	public void execute() throws MojoExecutionException {
		this.validateInputs();

		final String finalGuard = this.resolveGuardCode();

		File tempSteamScript = null;
		File effectiveBuildScript = this.buildScript;

		try {
			if (this.filterVdfs) {
				effectiveBuildScript = this.prepareFilteredVdfs();
			}

			final boolean cachedLogin = this.tryCachedLogin();

			if (cachedLogin) {
				this.getLog().info("Using cached SteamCMD login");
			} else {
				this.getLog().info("Cached login not available, using password login");
			}

			tempSteamScript = this.createSteamScript(finalGuard, effectiveBuildScript, cachedLogin);
			this.runSteamCmd(tempSteamScript);
		} catch (final Exception e) {
			throw new MojoExecutionException("Steam deployment failed", e);
		} finally {
			if (tempSteamScript != null && tempSteamScript.exists() && !tempSteamScript.delete()) {
				this.getLog().warn("Could not delete temp Steam script: " + tempSteamScript);
			}
		}

		this.getLog().info("Steam deployment completed successfully");
	}

	private void validateInputs() throws MojoExecutionException {
		if (!this.buildScript.exists()) {
			throw new MojoExecutionException("Build script not found: " + this.buildScript);
		}

		if (this.username == null) {
			final Server server = this.resolveServer();
			if (server == null || server.getUsername() == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find username.");
			}
			this.username = server.getUsername();
		}

		if (this.password == null) {
			final Server server = this.resolveServer();
			if (server == null || server.getPassword() == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find password.");
			}
			this.password = server.getPassword();
		}

		user = user == null || user.isBlank() ? null : user;
		usingUser |= user != null;
	}

	private Server resolveServer() {
		if (this.serverId == null || this.settings == null) {
			return null;
		}
		return this.settings.getServer(this.serverId);
	}

	private String resolveGuardCode() {
		if (this.guardCode != null && !this.guardCode.isBlank()) {
			return this.guardCode;
		}

		final String env = System.getenv("STEAM_GUARD");
		if (env != null && !env.isBlank()) {
			return env;
		}

		return null;
	}

	private File prepareFilteredVdfs() throws IOException, MojoExecutionException {
		final Path targetSteamDir = this.buildDirectory.toPath().resolve("steam");
		Files.createDirectories(targetSteamDir);

		this.getLog().info("Filtering Steam VDF files into: " + targetSteamDir);

		// Step 1: filter the main app build file
		final Path filteredAppBuild = targetSteamDir.resolve(this.buildScript.getName());
		String appBuildContent = Files.readString(this.buildScript.toPath(), StandardCharsets.UTF_8);
		appBuildContent = this.replacePlaceholders(appBuildContent, this.buildFilterValues());
		Files.writeString(filteredAppBuild, appBuildContent, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(filteredAppBuild, Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));

		// Step 2: find referenced depot files and filter them too
		// Matches lines like: "1234561" "depot_build_1234561.vdf"
		final Pattern depotPattern = Pattern.compile("\"\\d+\"\\s*\"([^\"]+\\.vdf)\"");
		final Matcher matcher = depotPattern.matcher(appBuildContent);

		while (matcher.find()) {
			final String depotFileName = matcher.group(1);

			final Path sourceDepot = this.buildScript.toPath().getParent().resolve(depotFileName);
			final Path targetDepot = targetSteamDir.resolve(depotFileName);

			if (!Files.exists(sourceDepot)) {
				throw new MojoExecutionException("Referenced depot VDF not found: " + sourceDepot);
			}

			String depotContent = Files.readString(sourceDepot, StandardCharsets.UTF_8);
			depotContent = this.replacePlaceholders(depotContent, this.buildFilterValues());
			Files.writeString(targetDepot, depotContent, StandardCharsets.UTF_8);
			Files.setPosixFilePermissions(targetDepot, Set.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));

			this.getLog().info("Filtered depot VDF: " + targetDepot);
		}

		return filteredAppBuild.toFile();
	}

	private Map<String, String> buildFilterValues() {
		final Map<String, String> values = new HashMap<>();

		if (this.filters != null) {
			values.putAll(this.filters);
		}

		values.putIfAbsent("project.basedir", this.session.getBasedir().getAbsolutePath());
		values.putIfAbsent("project.build.directory", this.buildDirectory.getAbsolutePath());
		values.putIfAbsent("project.version", this.session.getVersion());
		values.putIfAbsent("project.artifactId", this.session.getArtifactId());
		values.putIfAbsent("project.groupId", this.session.getGroupId());

		return values;
	}

	private String replacePlaceholders(final String content, final Map<String, String> values)
			throws MojoExecutionException {
		final Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
		final StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			final String key = matcher.group(1);
			final String replacement = values.get(key);

			if (replacement == null) {
				throw new MojoExecutionException("No filter value provided for placeholder @" + key + "@");
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);
		return result.toString();
	}

	private File createSteamScript(final String guard, final File effectiveBuildScript, final boolean cachedLogin)
			throws IOException {

		final Path steamDir = this.buildDirectory.toPath().resolve("steam");
		Files.createDirectories(steamDir);

		final Path scriptPath = steamDir.resolve("steam-build.txt");
		final File script = scriptPath.toFile();
		script.deleteOnExit();

		Files.setPosixFilePermissions(script.toPath(), Set.of(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));

		try (final PrintWriter writer = new PrintWriter(new FileWriter(script, StandardCharsets.UTF_8))) {
			writer.print("@ShutdownOnFailedCommand 1");
			writer.println();
			writer.print("@NoPromptForPassword 1");
			writer.println();

			writer.print("login " + this.username);

			if (!cachedLogin) {
				writer.print(" " + this.password);

				if (guard != null && !guard.isBlank()) {
					writer.print(" " + guard);
				}
			}

			writer.println();
			writer.println("run_app_build " + effectiveBuildScript.getAbsolutePath());
			writer.println("quit");
		}

		return script;
	}

	private void runSteamCmd(final File script) throws IOException, InterruptedException, MojoExecutionException {
		final ProcessBuilder pb = usingUser
				? new ProcessBuilder("sudo", "-n", "-u", user, this.steamcmdPath, "+runscript",
						script.getAbsolutePath())
				: new ProcessBuilder(this.steamcmdPath, "+runscript", script.getAbsolutePath());

		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

		final Process process = pb.start();
		final int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new MojoExecutionException("SteamCMD exited with code: " + exitCode);
		}
	}

	private boolean tryCachedLogin() throws IOException, InterruptedException {
		final ProcessBuilder pb = usingUser
				? new ProcessBuilder("sudo", "-n", "-u", user, this.steamcmdPath, "+login", this.username, "+quit")
				: new ProcessBuilder(this.steamcmdPath, "+login", this.username, "+quit");

		pb.redirectErrorStream(true);
		pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
		final Process p = pb.start();
		final int code = p.waitFor();

		return code == 0;
	}

}