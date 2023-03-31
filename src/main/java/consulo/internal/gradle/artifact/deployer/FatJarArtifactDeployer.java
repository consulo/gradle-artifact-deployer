package consulo.internal.gradle.artifact.deployer;

import com.google.common.io.MoreFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author VISTALL
 * @since 31/03/2023
 */
public class FatJarArtifactDeployer extends Deployer
{
	public static void main(String[] args) throws Exception
	{
		new FatJarArtifactDeployer().run(args);
	}

	@Override
	protected void postProcess(Path target, List<MavenArtifact> jarLibsForDeploy, String version) throws Exception
	{
		Path fatJarDir = target.resolve("gradle-all");
		Path fatJarSourceDir = target.resolve("gradle-all-sources");

		if(Files.exists(fatJarDir))
		{
			MoreFiles.deleteRecursively(fatJarDir);
		}

		Files.createDirectory(fatJarDir);

		if(Files.exists(fatJarSourceDir))
		{
			MoreFiles.deleteRecursively(fatJarSourceDir);
		}

		Files.createDirectory(fatJarSourceDir);

		for(MavenArtifact mavenArtifact : jarLibsForDeploy)
		{
			unzip(mavenArtifact.jar, fatJarDir, s ->
			{
				// we need skip slf4j impl since it will be override our impl
				if(s.startsWith("org/slf4j/"))
				{
					return true;
				}
				return false;
			});

			if(mavenArtifact.sourcesJar != null)
			{
				unzip(mavenArtifact.sourcesJar, fatJarSourceDir, s -> false);
			}
		}

		Path fatJar = target.resolve("gradle-all-" + version + ".jar");
		Path fatSourceJar = target.resolve("gradle-all-" + version + "-sources.jar");

		zipDirectory(fatJar, fatJarDir);
		zipDirectory(fatSourceJar, fatJarSourceDir);

		jarLibsForDeploy.clear();

		MavenArtifact artifact = new MavenArtifact(fatJar);
		artifact.sourcesJar = fatSourceJar;

		jarLibsForDeploy.add(artifact);
	}
}
