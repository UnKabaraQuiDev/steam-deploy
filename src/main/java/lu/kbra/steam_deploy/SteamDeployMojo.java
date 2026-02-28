package lu.kbra.steam_deploy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Settings;

@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true)
public class SteamDeployMojo extends AbstractMojo {

	@Parameter(property = "steamcmdPath", required = true)
	private String steamcmdPath;

	@Parameter(property = "username", required = false)
	private String username;
	@Parameter(property = "password", required = false)
	private String password;
	@Parameter(property = "steam.guard", required = false)
	private String guardCode;

	@Parameter(property = "buildScript", required = true)
	private File buildScript;

	@Parameter(property = "serverId", required = false)
	private String serverId;
	@Parameter(defaultValue = "${settings}", readonly = true)
	private Settings settings;

	@Override
	public void execute() throws MojoExecutionException {
		this.validateInputs();

		final String finalGuard = this.resolveGuardCode();

		File tempScript = null;
		try {
			tempScript = this.createSteamScript(finalGuard);
			this.runSteamCmd(tempScript);
		} catch (final Exception e) {
			throw new MojoExecutionException("Steam deployment failed", e);
		} finally {
			if (tempScript != null && tempScript.exists()) {
				tempScript.delete();
			}
		}

		this.getLog().info("Steam deployment completed successfully");
	}

	private void validateInputs() throws MojoExecutionException {
		if (!this.buildScript.exists()) {
			throw new MojoExecutionException("build script not found: " + this.buildScript);
		}

		if (username == null) {
			if (serverId == null || settings.getServer(serverId) == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find username.");
			}
			username = settings.getServer(serverId).getUsername();
		}
		if (password == null) {
			if (serverId == null || settings.getServer(serverId) == null) {
				throw new MojoExecutionException("Server id not provided or invalid, cannot find password.");
			}
			password = settings.getServer(serverId).getPassword();
		}
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

	private File createSteamScript(final String guard) throws IOException {
		final File script = Files.createTempFile("steam-build", ".txt").toFile();

		try (PrintWriter writer = new PrintWriter(new FileWriter(script))) {

			writer.print("login " + this.username + " " + this.password);

			if (guard != null) {
				writer.print(" " + guard);
			}

			writer.println();
			writer.println("run_app_build " + this.buildScript.getAbsolutePath());
			writer.println("quit");
		}

		return script;
	}

	private void runSteamCmd(final File script) throws IOException, InterruptedException, MojoExecutionException {
		final ProcessBuilder pb = new ProcessBuilder(this.steamcmdPath, "+runscript",
				script.getAbsolutePath());

		pb.inheritIO();

		final Process process = pb.start();
		final int exitCode = process.waitFor();

		if (exitCode != 0) {
			throw new MojoExecutionException("SteamCMD exited with code: " + exitCode);
		}
	}
}