package lu.kbra.steam_deploy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
	private boolean filterVdfs = true;

	@Parameter
	private Map<String, String> filters = new HashMap<>();

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject session;

	@Override
	public void execute() throws MojoExecutionException {
		validateInputs();

		final String finalGuard = resolveGuardCode();

		File tempSteamScript = null;
		File effectiveBuildScript = this.buildScript;

//		System.out.println("Using:\n" + filters.entrySet().stream().map(c -> c.getKey() + " = " + c.getValue())
//				.collect(Collectors.joining("\n")));

		try {
			if (filterVdfs) {
				effectiveBuildScript = prepareFilteredVdfs();
			}

			tempSteamScript = createSteamScript(finalGuard, effectiveBuildScript);
			runSteamCmd(tempSteamScript);

		} catch (Exception e) {
			throw new MojoExecutionException("Steam deployment failed", e);
		} finally {
			if (tempSteamScript != null && tempSteamScript.exists() && !tempSteamScript.delete()) {
				getLog().warn("Could not delete temp Steam script: " + tempSteamScript);
			}
		}

		getLog().info("Steam deployment completed successfully");
	}

	private void validateInputs() throws MojoExecutionException {
		if (!this.buildScript.exists()) {
			throw new MojoExecutionException("Build script not found: " + this.buildScript);
		}

		if (username == null) {
			Server server = resolveServer();
			if (server == null || server.getUsername() == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find username.");
			}
			username = server.getUsername();
		}

		if (password == null) {
			Server server = resolveServer();
			if (server == null || server.getPassword() == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find password.");
			}
			password = server.getPassword();
		}
	}

	private Server resolveServer() {
		if (serverId == null || settings == null) {
			return null;
		}
		return settings.getServer(serverId);
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

	/**
	 * Copies and filters the main app_build VDF and any referenced depot VDFs into
	 * target/steam.
	 *
	 * Returns the filtered app_build file to use with SteamCMD.
	 */
	private File prepareFilteredVdfs() throws IOException, MojoExecutionException {
		final Path targetSteamDir = buildDirectory.toPath().resolve("steam");
		Files.createDirectories(targetSteamDir);

		getLog().info("Filtering Steam VDF files into: " + targetSteamDir);

		// Step 1: filter the main app build file
		final Path filteredAppBuild = targetSteamDir.resolve(buildScript.getName());
		String appBuildContent = Files.readString(buildScript.toPath(), StandardCharsets.UTF_8);
		appBuildContent = replacePlaceholders(appBuildContent, buildFilterValues());
		Files.writeString(filteredAppBuild, appBuildContent, StandardCharsets.UTF_8);

		// Step 2: find referenced depot files and filter them too
		// Matches lines like: "1234561" "depot_build_1234561.vdf"
		final Pattern depotPattern = Pattern.compile("\"\\d+\"\\s*\"([^\"]+\\.vdf)\"");
		final Matcher matcher = depotPattern.matcher(appBuildContent);

		while (matcher.find()) {
			final String depotFileName = matcher.group(1);

			final Path sourceDepot = buildScript.toPath().getParent().resolve(depotFileName);
			final Path targetDepot = targetSteamDir.resolve(depotFileName);

			if (!Files.exists(sourceDepot)) {
				throw new MojoExecutionException("Referenced depot VDF not found: " + sourceDepot);
			}

			String depotContent = Files.readString(sourceDepot, StandardCharsets.UTF_8);
			depotContent = replacePlaceholders(depotContent, buildFilterValues());
			Files.writeString(targetDepot, depotContent, StandardCharsets.UTF_8);

			getLog().info("Filtered depot VDF: " + targetDepot);
		}

		return filteredAppBuild.toFile();
	}

	private Map<String, String> buildFilterValues() {
		final Map<String, String> values = new HashMap<>();

		if (filters != null) {
			values.putAll(filters);
		}

		values.putIfAbsent("project.basedir", session.getBasedir().getAbsolutePath());
		values.putIfAbsent("project.build.directory", buildDirectory.getAbsolutePath());
		values.putIfAbsent("project.version", session.getVersion());
		values.putIfAbsent("project.artifactId", session.getArtifactId());
		values.putIfAbsent("project.groupId", session.getGroupId());

		return values;
	}

	private String replacePlaceholders(String content, Map<String, String> values) throws MojoExecutionException {
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

	private File createSteamScript(final String guard, final File effectiveBuildScript) throws IOException {
		final File script = Files.createTempFile("steam-build", ".txt").toFile();

		try (PrintWriter writer = new PrintWriter(new FileWriter(script, StandardCharsets.UTF_8))) {
			writer.print("login " + this.username + " " + this.password);

			if (guard != null) {
				writer.print(" " + guard);
			}

			writer.println();
			writer.println("run_app_build " + effectiveBuildScript.getAbsolutePath());
			writer.println("quit");
		}

		return script;
	}

	private void runSteamCmd(final File script) throws IOException, InterruptedException, MojoExecutionException {
		final ProcessBuilder pb = new ProcessBuilder(this.steamcmdPath, "+runscript", script.getAbsolutePath());

		pb.inheritIO();

		final Process process = pb.start();
		final int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new MojoExecutionException("SteamCMD exited with code: " + exitCode);
		}
	}

}